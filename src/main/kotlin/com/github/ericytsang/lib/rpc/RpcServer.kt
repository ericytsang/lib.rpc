package com.github.ericytsang.lib.rpc

import com.github.ericytsang.lib.modem.Modem
import com.github.ericytsang.lib.net.connection.Connection
import java.io.Closeable
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable

class RpcServer<in Context>(val modem:Modem,private val context:Context):Closeable
{
    private var isClosing = false

    override fun close() = synchronized(server)
    {
        isClosing = true
        modem.close()
        server.join()
    }

    private val server = object:Thread()
    {
        override fun run()
        {
            while (true)
            {
                val connection = try
                {
                    modem.accept()
                }
                catch (ex:Exception)
                {
                    if (isClosing) break else throw ex
                }
                ConnectionHandler(connection).start()
            }
        }

        init
        {
            start()
        }
    }

    inner class ConnectionHandler(val connection:Connection):Thread()
    {
        override fun run()
        {
            connection.use()
            {
                connection ->
                val rpcFunctionCall = try
                {
                    connection.inputStream
                        .let(::ObjectInputStream)
                        .use {it.readObject()}
                }
                catch (ex:Exception)
                {
                    if (isClosing) return else throw ex
                }
                val result = try
                {
                    @Suppress("UNCHECKED_CAST")
                    rpcFunctionCall as RpcFunction<Context,Serializable>
                    RpcResult.Success(rpcFunctionCall.doInServer(context))
                }
                catch (ex:Exception)
                {
                    RpcResult.Failure(ex)
                }
                try
                {
                    connection.outputStream
                        .let(::ObjectOutputStream)
                        .use {it.writeObject(result)}
                }
                catch (ex:Exception)
                {
                    if (isClosing) return else throw ex
                }
            }
        }
    }
}
