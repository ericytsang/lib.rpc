package com.github.ericytsang.lib.rpc

import com.github.ericytsang.lib.modem.Modem
import com.github.ericytsang.lib.net.connection.Connection
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable
import java.rmi.RemoteException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

abstract class RpcFunction<in Context,out Return:Serializable?>:Serializable
{
    open fun callFromClient(modem:Modem):Return
    {
        val resultQ = ArrayBlockingQueue<()->RpcResult>(1)
        val worker = object:Thread()
        {
            val postInvocationFunctionLatch = CountDownLatch(1)
            var postInvocationFunction:()->Unit = {}
                get()
                {
                    postInvocationFunctionLatch.await()
                    return field
                }
                set(value) = synchronized(postInvocationFunctionLatch)
                {
                    if (postInvocationFunctionLatch.count == 1L)
                    {
                        field = value
                        postInvocationFunctionLatch.countDown()
                    }
                }

            val thisAsSerialized = run()
            {
                val byteO = ByteArrayOutputStream()
                ObjectOutputStream(byteO).use {it.writeObject(this@RpcFunction)}
                byteO.toByteArray()
            }

            lateinit var connection:Connection

            override fun run()
            {
                modem.connect(Unit).use()
                {
                    connection ->
                    this.connection = connection

                    // send parameters
                    connection.outputStream.write(thisAsSerialized)

                    // prepare to read result asynchronously
                    val resultReader = thread()
                    {
                        // read result from remote
                        @Suppress("UNCHECKED_CAST")
                        val result = try
                        {
                            connection.inputStream
                                .let(::ObjectInputStream)
                                .let {it.readObject() as RpcResult}
                                .let {{it}}
                        }
                        catch (ex:Exception)
                        {
                            {throw CommunicationException(ex)}
                        }

                        // initialize postInvocationFunction to release latch
                        postInvocationFunction = {
                            connection.outputStream.write(100)
                        }

                        // return result to parent thread
                        resultQ.put(result)
                    }

                    // execute the post-invocation function
                    postInvocationFunction()
                    resultReader.join()
                }
            }

            override fun interrupt()
            {
                postInvocationFunction = {
                    connection.outputStream.write(100)
                }
            }

            init
            {
                start()
            }
        }
        try
        {
            worker.join()
        }
        catch (ex:InterruptedException)
        {
            worker.interrupt()
        }
        val result = resultQ.take().invoke()
        @Suppress("UNCHECKED_CAST")
        when (result)
        {
            is RpcResult.Failure -> throw RemoteException("remote exception occurred",result.throwable)
            is RpcResult.Success -> return result.value as Return
        }
    }

    abstract fun doInServer(context:Context):Return

    class CommunicationException(cause:Throwable):RuntimeException(cause)
}
