package com.github.ericytsang.lib.rpc

import com.github.ericytsang.lib.concurrent.future
import com.github.ericytsang.lib.modem.Modem
import com.github.ericytsang.lib.net.connection.TcpConnection
import org.junit.After
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.rmi.RemoteException

class RpcServerTest
{
    companion object
    {
        const val PORT = 53454
    }

    val connectionMaker = future {
        ServerSocket(PORT).use {it.accept()}
    }
    val con1 = TcpConnection(Socket(InetAddress.getLocalHost(),PORT))
    val con2 = TcpConnection(connectionMaker.get())
    val modem1 = Modem(con1)
    val modem2 = Modem(con2)

    val rpcServer = RpcServer(modem1,5)

    @After
    fun teardown()
    {
        rpcServer.close()
        con1.close()
        con2.close()
    }

    @Test
    fun generalTest()
    {
        val functionCall = TestAddRpcFunction(79)
        assert(functionCall.callFromClient(modem2) == 79+5)
    }

    @Test
    fun remoteExceptionTest()
    {
        val functionCall = TestExceptionRpcFunction(79)
        try
        {
            functionCall.callFromClient(modem2)
            assert(false)
        }
        catch (ex:RemoteException)
        {
            ex.printStackTrace()
            // ignore
        }
    }

    class TestAddRpcFunction(val number:Int):RpcFunction<Int,Int>()
    {
        override fun doInServer(context:Int):Int
        {
            return context+number
        }
    }

    class TestExceptionRpcFunction(val number:Int):RpcFunction<Int,Int>()
    {
        override fun doInServer(context:Int):Int
        {
            throw IllegalArgumentException()
        }
    }
}
