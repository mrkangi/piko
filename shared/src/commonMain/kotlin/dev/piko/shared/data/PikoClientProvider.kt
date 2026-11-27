package dev.piko.shared.data

import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.PikPakException
import io.github.nihildigit.pikpak.Session
import kotlinx.coroutines.flow.StateFlow

interface PikoClientProvider {
    val currentClient: StateFlow<PikPakClient?>

    /**
     * 服务端在普通请求上拒绝当前会话后调用。实现方应用已保存的密码重新建立会话；
     * [rejectedSession] 是失败请求发出时的会话快照，别的协程已经换掉它时直接返回成功。
     * 默认实现不恢复，供只读测试替身使用。
     */
    suspend fun recoverAuthentication(client: PikPakClient, rejectedSession: Session?): Boolean = false
}

/**
 * PikPak 把会话失效也可能报成裸 HTTP 400，不带 error_envelope；验证码失效同样是 400。
 * 这些都属于「重新登录一次可能恢复」的错误，目录不存在之类的业务拒绝不在此列。
 */
internal fun PikPakException.isAuthenticationFailure(): Boolean =
    httpStatus == 401 || isRefreshTokenInvalid || isCaptchaRequired ||
        (httpStatus == 400 && errorCode == -1)
