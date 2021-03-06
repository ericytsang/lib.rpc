package com.github.ericytsang.lib.rpc

import com.github.ericytsang.lib.concurrent.future
import com.github.ericytsang.lib.modem.Modem
import com.github.ericytsang.lib.net.connection.TcpConnection
import org.junit.After
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class RpcServerTest
{
    companion object
    {
        val PORT = (50000..60000).toList().let {it[Math.floor(Math.random()*it.size).toInt()]}
    }

    val connectionMaker = future {
        ServerSocket(PORT).use {it.accept()}
    }
    val con1 = TcpConnection(Socket(InetAddress.getLocalHost(),PORT))
    val con2 = TcpConnection(connectionMaker.get())
    val modem1 = Modem(con1)
    val modem2 = Modem(con2)

    var shutdownCalled = false

    val rpcServer = object:RpcServer<Int>(modem1,5)
    {
        override fun onShutdown(wasClosedLocally:Boolean,cause:Exception)
        {
            shutdownCalled = true
        }
    }

    @After
    fun teardown()
    {
        rpcServer.close()
        con1.close()
        con2.close()
        TestUtils.assertAllWorkerThreadsDead(emptySet(),200)
    }

    @Test
    fun returnNonNullTest()
    {
        assert(TestAddRpcFunction(79).callFromClient(modem2) == 79+5)
        assert(TestAddRpcFunction(90).callFromClient(modem2) == 90+5)
        assert(TestAddRpcFunction(79).callFromClient(modem2) == 79+5)
        assert(TestAddRpcFunction(90).callFromClient(modem2) == 90+5)
        assert(TestAddRpcFunction(79).callFromClient(modem2) == 79+5)
        assert(TestAddRpcFunction(90).callFromClient(modem2) == 90+5)
        assert(TestAddRpcFunction(79).callFromClient(modem2) == 79+5)
        assert(TestAddRpcFunction(90).callFromClient(modem2) == 90+5)
    }

    @Test
    fun returnNullTest()
    {
        val functionCall = TestNummRpcFunction(79)
        assert(functionCall.callFromClient(modem2) == null)
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
        catch (ex:RpcFunction.RemoteException)
        {
            println("==== expected exception start ====")
            ex.printStackTrace(System.out)
            println("==== expected exception end ====")
        }
    }

    @Test
    fun interruptedTest()
    {
        val functionCall = TestSleepRpcFunction(5000)
        val currentThread = Thread.currentThread()
        val interrupter = thread {
            Thread.sleep(100)
            currentThread.interrupt()
        }
        try
        {
            functionCall.callFromClient(modem2)
        }
        catch (ex:AssertionError)
        {
            throw ex
        }
        catch (ex:Exception)
        {
            println("==== expected exception start ====")
            ex.printStackTrace(System.out)
            println("==== expected exception end ====")
        }
        interrupter.join()
    }

    @Test
    fun preemptiveInterruptedTest()
    {
        val functionCall = TestSleepRpcFunction(5000)
        try
        {
            Thread.currentThread().interrupt()
            functionCall.callFromClient(modem2)
        }
        catch (ex:AssertionError)
        {
            throw ex
        }
        catch (ex:Exception)
        {
            println("==== expected exception start ====")
            ex.printStackTrace(System.out)
            println("==== expected exception end ====")
        }
    }

    @Test
    fun remoteInterruptTest()
    {
        val functionCall = TestRemoteInterrupt(5000)
        functionCall.callFromClient(modem2)
        check(Thread.interrupted())
    }

    @Test
    fun underlyingModemDiesCallsOnShutdown()
    {
        modem2.close()
        Thread.sleep(100)
        check(shutdownCalled)
    }

    @Test
    fun underlyingModemDiesDuringFunctionCall()
    {
        val functionCall = TestSleepRpcFunction(10000)
        val t = thread {
            Thread.sleep(100)
            modem1.close()
        }
        try
        {
            functionCall.callFromClient(modem2)
            assert(false)
        }
        catch (ex:RpcFunction.CommunicationException)
        {
            println("==== expected exception start ====")
            ex.printStackTrace(System.out)
            println("==== expected exception end ====")
        }
        t.join()
    }

    @Test
    fun shutdownServerDuringLongRunningFunctionCall()
    {
        val functionCall = TestSleepRpcFunction(100000)
        val t = thread {
            functionCall.callFromClient(modem2)
        }
        Thread.sleep(100)
        rpcServer.close()
        t.join()
    }

    class TestAddRpcFunction(val number:Int):RpcFunction<Int,Int>()
    {
        override fun doInServer(context:Int):Int
        {
            return context+number
        }
    }

    class TestNummRpcFunction(val number:Int):RpcFunction<Int,Int?>()
    {
        override fun doInServer(context:Int):Int?
        {
            return null
        }
    }

    class TestExceptionRpcFunction(val number:Int):RpcFunction<Int,Int>()
    {
        override fun doInServer(context:Int):Int
        {
            throw IllegalArgumentException()
        }
    }

    class TestRemoteInterrupt(val number:Int):RpcFunction<Int,Int>()
    {
        override fun doInServer(context:Int):Int
        {
            Thread.currentThread().interrupt()
            return 4
        }
    }

    class TestSleepRpcFunction(val number:Long):RpcFunction<Int,Int>()
    {
        override fun doInServer(context:Int):Int
        {
            Thread.sleep(number)
            return 2
        }

        override fun stopDoInServer(doInServerThread:Thread)
        {
            doInServerThread.interrupt()
            doInServerThread.join()
        }
    }
}
