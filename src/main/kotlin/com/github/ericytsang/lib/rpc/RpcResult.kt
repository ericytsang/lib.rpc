package com.github.ericytsang.lib.rpc

import java.io.Serializable

sealed class RpcResult:Serializable
{
    class Failure(val throwable:Throwable):RpcResult()
    class Success(val value:Serializable):RpcResult()
}
