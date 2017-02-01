package com.github.ericytsang.lib.rpc

import com.github.ericytsang.lib.modem.Modem
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable
import java.rmi.RemoteException

abstract class RpcFunction<in Context,out Return:Serializable?>:Serializable
{
    fun callFromClient(modem:Modem):Return
    {
        val connection = modem.connect(Unit)
        val byteO = ByteArrayOutputStream()
        val objO = ObjectOutputStream(byteO)
        objO.use {it.writeObject(this)}
        connection.outputStream.use {it.write(byteO.toByteArray())}
        @Suppress("UNCHECKED_CAST")
        val result = connection.inputStream
            .let(::ObjectInputStream)
            .use {it.readObject()} as RpcResult
        connection.close()
        @Suppress("UNCHECKED_CAST")
        when (result)
        {
            is RpcResult.Failure -> throw RemoteException("remote exception occurred",result.throwable)
            is RpcResult.Success -> return result.value as Return
        }
    }

    abstract fun doInServer(context:Context):Return
}
