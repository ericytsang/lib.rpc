package com.github.ericytsang.lib.rpc

import java.io.Serializable

sealed class RpcResult:Serializable
{
    abstract val wasInterruptIssued:Boolean
    abstract val isInterrupted:Boolean
    class Failure(val throwable:Throwable,override val isInterrupted:Boolean,override val wasInterruptIssued:Boolean):RpcResult()
    class Success(val value:Serializable?,override val isInterrupted:Boolean,override val wasInterruptIssued:Boolean):RpcResult()
}
