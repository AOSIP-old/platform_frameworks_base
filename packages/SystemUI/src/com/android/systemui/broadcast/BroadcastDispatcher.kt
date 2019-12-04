/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.systemui.broadcast

import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.UserHandle
import android.text.TextUtils
import android.util.Log
import android.util.SparseArray
import com.android.internal.annotations.VisibleForTesting
import com.android.systemui.Dumpable
import com.android.systemui.dagger.qualifiers.BgLooper
import com.android.systemui.dagger.qualifiers.MainHandler
import java.io.FileDescriptor
import java.io.PrintWriter
import javax.inject.Inject
import javax.inject.Singleton

data class ReceiverData(
    val receiver: BroadcastReceiver,
    val filter: IntentFilter,
    val handler: Handler,
    val user: UserHandle
)

private const val MSG_ADD_RECEIVER = 0
private const val MSG_REMOVE_RECEIVER = 1
private const val MSG_REMOVE_RECEIVER_FOR_USER = 2
private const val TAG = "BroadcastDispatcher"
private const val DEBUG = false

/**
 * SystemUI master Broadcast Dispatcher.
 *
 * This class allows [BroadcastReceiver] to register and centralizes registrations to [Context]
 * from SystemUI. That way the number of calls to [BroadcastReceiver.onReceive] can be reduced for
 * a given broadcast.
 *
 * Use only for IntentFilters with actions and optionally categories. It does not support,
 * permissions, schemes, data types, data authorities or priority different than 0.
 * Cannot be used for getting sticky broadcasts.
 */
@Singleton
open class BroadcastDispatcher @Inject constructor (
    private val context: Context,
    @MainHandler private val mainHandler: Handler,
    @BgLooper private val bgLooper: Looper
) : Dumpable {

    // Only modify in BG thread
    private val receiversByUser = SparseArray<UserBroadcastDispatcher>(20)

    /**
     * Register a receiver for broadcast with the dispatcher
     *
     * @param receiver A receiver to dispatch the [Intent]
     * @param filter A filter to determine what broadcasts should be dispatched to this receiver.
     *               It will only take into account actions and categories for filtering. It must
     *               have at least one action.
     * @param handler A handler to dispatch [BroadcastReceiver.onReceive]. By default, it is the
     *                main handler. Pass `null` to use the default.
     * @param user A user handle to determine which broadcast should be dispatched to this receiver.
     *             By default, it is the current user.
     * @throws IllegalArgumentException if the filter has other constraints that are not actions or
     *                                  categories or the filter has no actions.
     */
    @JvmOverloads
    fun registerReceiver(
        receiver: BroadcastReceiver,
        filter: IntentFilter,
        handler: Handler? = mainHandler,
        user: UserHandle = context.user
    ) {
        checkFilter(filter)
        this.handler
                .obtainMessage(MSG_ADD_RECEIVER,
                ReceiverData(receiver, filter, handler ?: mainHandler, user))
                .sendToTarget()
    }

    private fun checkFilter(filter: IntentFilter) {
        val sb = StringBuilder()
        if (filter.countActions() == 0) sb.append("Filter must contain at least one action. ")
        if (filter.countDataAuthorities() != 0) sb.append("Filter cannot contain DataAuthorities. ")
        if (filter.countDataPaths() != 0) sb.append("Filter cannot contain DataPaths. ")
        if (filter.countDataSchemes() != 0) sb.append("Filter cannot contain DataSchemes. ")
        if (filter.countDataTypes() != 0) sb.append("Filter cannot contain DataTypes. ")
        if (filter.priority != 0) sb.append("Filter cannot modify priority. ")
        if (!TextUtils.isEmpty(sb)) throw IllegalArgumentException(sb.toString())
    }

    /**
     * Unregister receiver for all users.
     * <br>
     * This will remove every registration of [receiver], not those done just with [UserHandle.ALL].
     *
     * @param receiver The receiver to unregister. It will be unregistered for all users.
     */
    fun unregisterReceiver(receiver: BroadcastReceiver) {
        handler.obtainMessage(MSG_REMOVE_RECEIVER, receiver).sendToTarget()
    }

    /**
     * Unregister receiver for a particular user.
     *
     * @param receiver The receiver to unregister. It will be unregistered for all users.
     * @param user The user associated to the registered [receiver]. It can be [UserHandle.ALL].
     */
    fun unregisterReceiverForUser(receiver: BroadcastReceiver, user: UserHandle) {
        handler.obtainMessage(MSG_REMOVE_RECEIVER_FOR_USER, user.identifier, 0, receiver)
                .sendToTarget()
    }

    @VisibleForTesting
    protected open fun createUBRForUser(userId: Int) =
            UserBroadcastDispatcher(context, userId, mainHandler, bgLooper)

    override fun dump(fd: FileDescriptor, pw: PrintWriter, args: Array<out String>) {
        pw.println("Broadcast dispatcher:")
        for (index in 0 until receiversByUser.size()) {
            pw.println("  User ${receiversByUser.keyAt(index)}")
            receiversByUser.valueAt(index).dump(fd, pw, args)
        }
    }

    private val handler = object : Handler(bgLooper) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MSG_ADD_RECEIVER -> {
                    val data = msg.obj as ReceiverData
                    val userId = data.user.identifier
                    if (userId < UserHandle.USER_ALL) {
                        if (DEBUG) Log.w(TAG, "Register receiver for invalid user: $userId")
                        return
                    }
                    val uBR = receiversByUser.get(userId, createUBRForUser(userId))
                    receiversByUser.put(userId, uBR)
                    uBR.registerReceiver(data)
                }

                MSG_REMOVE_RECEIVER -> {
                    for (it in 0 until receiversByUser.size()) {
                        receiversByUser.valueAt(it).unregisterReceiver(msg.obj as BroadcastReceiver)
                    }
                }

                MSG_REMOVE_RECEIVER_FOR_USER -> {
                    receiversByUser.get(msg.arg1)?.unregisterReceiver(msg.obj as BroadcastReceiver)
                }

                else -> super.handleMessage(msg)
            }
        }
    }
}
