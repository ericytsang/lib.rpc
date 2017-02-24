package com.github.ericytsang.lib.rpc

import com.github.ericytsang.lib.modem.Modem
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
            private val thisAsSerialized = run()
            {
                val byteO = ByteArrayOutputStream()
                ObjectOutputStream(byteO).use {it.writeObject(this@RpcFunction)}
                byteO.toByteArray()
            }

            private val doneLatch = CountDownLatch(1)

            override fun run()
            {
                modem.connect(Unit).use()
                {
                    connection ->

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

                        // release latch since we have the result
                        doneLatch.countDown()

                        // return result to parent thread
                        resultQ.put(result)
                    }

                    // execute the post-invocation function
                    doneLatch.await()
                    connection.outputStream.write(100)
                    resultReader.join()
                }
            }

            override fun interrupt()
            {
                doneLatch.countDown()
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
        val result = run()
        {
            var result:RpcResult
            while (true)
            {
                try
                {
                    result = resultQ.take().invoke()
                    break
                }
                catch (ex:InterruptedException)
                {
                    Thread.interrupted()
                }
            }
            result
        }
        try
        {
            @Suppress("UNCHECKED_CAST")
            when (result)
            {
                is RpcResult.Failure -> throw RemoteException("remote exception occurred",result.throwable)
                is RpcResult.Success -> return result.value as Return
            }
        }
        finally
        {
            if (result.isInterrupted)
            {
                Thread.currentThread().interrupt()
            }
        }
    }

    abstract fun doInServer(context:Context):Return

    class CommunicationException(cause:Throwable):RuntimeException(cause)
}
