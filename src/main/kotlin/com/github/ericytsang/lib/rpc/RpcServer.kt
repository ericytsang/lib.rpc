package com.github.ericytsang.lib.rpc

import com.github.ericytsang.lib.modem.Modem
import com.github.ericytsang.lib.net.connection.Connection
import com.github.ericytsang.lib.onlysetonce.OnlySetOnce
import java.io.Closeable
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

open class RpcServer<in Context>(val modem:Modem,private val context:Context):Closeable
{
    private var closeStackTrace:Array<StackTraceElement>? by OnlySetOnce()

    private val closeLock = ReentrantLock()

    override fun close() = closeLock.withLock()
    {
        try
        {
            closeStackTrace = Thread.currentThread().stackTrace
        }
        catch (ex:Exception)
        {
            // ignore
        }
        modem.close()
        if (Thread.currentThread() != server) server.join()
    }

    private val server:Thread = object:Thread()
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
                    val closeStackTrace = closeStackTrace
                    if (closeStackTrace != null)
                    {
                        onShutdown(true,IllegalArgumentException("server closed at ${closeStackTrace.joinToString("\n","\n","\n")}",ex))
                    }
                    else
                    {
                        close()
                        onShutdown(false,ex)
                    }
                    return
                }
                ConnectionHandler(connection).start()
            }
        }

        init
        {
            start()
        }
    }

    protected open fun onShutdown(wasClosedLocally:Boolean,cause:Exception)
    {
        if (!wasClosedLocally)
        {
            throw cause
        }
    }

    private fun callOnOngoingFunctionCallCommunicationException(cause:Exception)
    {
        val closeStackTrace = closeStackTrace
        if (closeStackTrace != null)
        {
            onOngoingFunctionCallCommunicationException(true,IllegalArgumentException("server closed at ${closeStackTrace.joinToString("\n","\n","\n")}",cause))
        }
        else
        {
            onOngoingFunctionCallCommunicationException(false,cause)
        }
    }

    protected open fun onOngoingFunctionCallCommunicationException(wasClosedLocally:Boolean,cause:Exception)
    {
        if (!wasClosedLocally)
        {
            throw cause
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
                        .readObject()
                }
                catch (ex:Exception)
                {
                    callOnOngoingFunctionCallCommunicationException(ex)
                    return
                }
                val resultQ = ArrayBlockingQueue<RpcResult>(1)
                val resultComputer = thread()
                {
                    val result = try
                    {
                        @Suppress("UNCHECKED_CAST")
                        rpcFunctionCall as RpcFunction<Context,Serializable>
                        RpcResult.Success(rpcFunctionCall.doInServer(context),Thread.interrupted())
                    }
                    catch (ex:Exception)
                    {
                        RpcResult.Failure(ex,Thread.interrupted())
                    }
                    while (true)
                    {
                        try
                        {
                            resultQ.put(result)
                            break
                        }
                        catch (ex:InterruptedException)
                        {
                            Thread.interrupted()
                        }
                    }
                }
                val interrupter = thread()
                {
                    if (connection.inputStream.read() != -1)
                    {
                        resultComputer.interrupt()
                    }
                }
                val result = resultQ.take()
                try
                {
                    connection.outputStream
                        .let(::ObjectOutputStream)
                        .writeObject(result)
                }
                catch (ex:Exception)
                {
                    callOnOngoingFunctionCallCommunicationException(ex)
                    return
                }
                resultComputer.join()
                interrupter.join()
            }
        }
    }
}
