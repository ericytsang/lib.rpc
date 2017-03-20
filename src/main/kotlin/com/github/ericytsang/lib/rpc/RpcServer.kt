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

    override fun close()
    {
        try
        {
            closeStackTrace = Thread.currentThread().stackTrace
            modem.close()
            if (Thread.currentThread() != server) server.join()
        }
        catch (ex:Exception)
        {
            // ignore
        }
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

    private inner class ConnectionHandler(val connection:Connection):Thread()
    {
        override fun run()
        {
            connection.use()
            {
                connection ->

                // receive the remote function call object.
                val rpcFunctionCall = try
                {
                    connection.inputStream
                        .let(::ObjectInputStream)
                        .readObject()
                }
                catch (ex:Exception)
                {
                    return
                }

                // locked after the rps function returns to make sure nothing
                // modifies the thread state
                val mutex = ReentrantLock()
                var wasInterruptIssued = false

                // computation result is put in here by the result computer
                // thread.
                val resultQ = ArrayBlockingQueue<RpcResult>(1)

                // compute the result of the function call.
                val resultComputer = thread()
                {
                    val (wasThrown,functionCallResult) = try
                    {
                        @Suppress("UNCHECKED_CAST")
                        rpcFunctionCall as RpcFunction<Context,Serializable>
                        val functionCallResult = rpcFunctionCall.doInServer(context)
                        false to functionCallResult
                    }
                    catch (ex:Exception)
                    {
                        true to ex
                    }
                    mutex.withLock()
                    {
                        val isInterrupted = Thread.interrupted()
                        val result = if (!wasThrown)
                        {
                            RpcResult.Success(functionCallResult,isInterrupted,wasInterruptIssued)
                        }
                        else
                        {
                            RpcResult.Failure(functionCallResult as Throwable,isInterrupted,wasInterruptIssued)
                        }
                        Thread.interrupted()
                        resultQ.put(result)
                    }
                }

                // interrupt or terminate the result computer as per connection
                // state.
                val interrupter = thread()
                {
                    // wait for message from remote before interrupting the
                    // thread that is computing the result.
                    if (connection.inputStream.read() != -1)
                    {
                        mutex.withLock()
                        {
                            wasInterruptIssued = true
                            resultComputer.interrupt()
                            Unit
                        }
                    }

                    // connection is terminated; stop the computing thread.
                    else
                    {
                        try
                        {
                            rpcFunctionCall as RpcFunction<*,*>
                            rpcFunctionCall.stopDoInServer(resultComputer)
                            if (resultComputer.isAlive)
                            {
                                throw RuntimeException("thread still alive after call to stopDoInServer:${resultComputer.stackTrace.joinToString("\n","\nvvvv\n","\n^^^^")}}")
                            }
                        }
                        catch (ex:ClassCastException)
                        {
                            // ignore...
                        }
                    }
                }

                // return the result to the remote caller
                val result = resultQ.take()
                try
                {
                    connection.outputStream
                        .buffered()
                        .let(::ObjectOutputStream)
                        .apply {
                            writeObject(result)
                            flush()
                        }
                }
                catch (ex:Exception)
                {
                    return
                }
                resultComputer.join()
                interrupter.join()
            }
        }
    }
}
