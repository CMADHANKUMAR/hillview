/*
 * Copyright (c) 2017 VMware Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.hillview;

import com.google.gson.JsonElement;
import com.google.gson.internal.Streams;
import com.google.gson.stream.JsonReader;
import io.reactivex.Observer;
import io.reactivex.disposables.Disposable;
import io.reactivex.subscribers.ResourceSubscriber;
import org.hillview.utils.Converters;
import org.hillview.utils.HillviewLogging;
import org.hillview.utils.Utilities;

import javax.websocket.*;
import javax.websocket.server.ServerEndpoint;
import java.io.*;

/**
 * A server which implements RPC calls between a web browser client and
 * a Java-based web server.  The web server may create a different
 * instance of this class for each request.  The client should
 * send exactly one request for each session; the server may send zero, one
 * or more replies for each session.  The client or server can both
 * choose to close the connection at any time.  This class must be public.
 */
@SuppressWarnings("WeakerAccess")
@ServerEndpoint(value = "/rpc")
public final class RpcServer {
    static private final int version = 2;

    @SuppressWarnings("unused")
    @OnOpen
    public void onOpen(Session session) {
        HillviewLogging.logger().info("Server {} new connection with client: {}",
                                    version, session.getId());
        RpcObjectManager.instance.addSession(session, null);
    }

    @SuppressWarnings("unused")
    @OnMessage
    public void onMessage(String message, Session session) {
        HillviewLogging.logger().info("New message from Client [{}]: {}", session.getId(), message);
        RpcRequest req;
        try {
            Reader reader = new StringReader(message);
            JsonReader jReader = new JsonReader(reader);
            JsonElement elem = Streams.parse(jReader);
            req = new RpcRequest(elem);
            if (RpcObjectManager.instance.getTarget(session) != null)
                throw new RuntimeException("Session already associated with a request!");
        } catch (Exception ex) {
            HillviewLogging.logger().error("Error processing json: ", ex);
            this.replyWithError(ex, session);
            return;
        }

        RpcRequestContext context = new RpcRequestContext(session);
        this.execute(req, context);
    }

    private void sendReply(RpcReply reply, Session session) {
        try {
            JsonElement json = reply.toJson();
            session.getBasicRemote().sendText(json.toString());
        } catch (Exception e) {
            HillviewLogging.logger().error("Could not send reply", e);
        }
    }

    private void execute(RpcRequest rpcRequest, RpcRequestContext context) {
        HillviewLogging.logger().info("Executing {}", rpcRequest);
        // Observable invoked when the source object has been obtained.
        Observer<RpcTarget> obs = new Observer<RpcTarget>() {
            @Override
            public void onComplete() {}

            @Override
            public void onError(Throwable throwable) {
                HillviewLogging.logger().error("Return exception", throwable);
                RpcReply reply = rpcRequest.createReply(throwable);
                Session session = context.getSessionIfOpen();
                if (session == null)
                    return;
                RpcServer.this.sendReply(reply, session);
                rpcRequest.syncCloseSession(session);
            }

            @Override
            public void onSubscribe(Disposable disposable) {

            }

            @Override
            public void onNext(RpcTarget rpcTarget) {
                if (context.session != null)
                    RpcObjectManager.instance.addSession(context.session, rpcTarget);
                // This function is responsible for sending the replies and closing the session.
                rpcTarget.execute(rpcRequest, context);
            }
        };
        RpcObjectManager.instance.retrieveTarget(rpcRequest.objectId, true, obs);
    }

    private void closeSession(final Session session) {
        try {
            if (session.isOpen())
                session.close();
            RpcObjectManager.instance.removeSession(session);
        } catch (Exception ex) {
            HillviewLogging.logger().error("Error closing context", ex);
        }
    }

    private void replyWithError(final Throwable th, final Session session) {
        final RpcReply reply = new RpcReply(
                -1, Converters.checkNull(Utilities.throwableToString(th)), true);
        this.sendReply(reply, session);
        this.closeSession(session);
    }

    @SuppressWarnings("unused")
    @OnClose
    public void onClose(final Session session, final CloseReason reason) {
        ResourceSubscriber sub = RpcObjectManager.instance.getSubscription(session);
        if (sub != null) {
            HillviewLogging.logger().info("Unsubscribing {}", this.toString());
            sub.dispose();
            RpcObjectManager.instance.removeSubscription(session);
        }

        if (reason.getCloseCode() != CloseReason.CloseCodes.NORMAL_CLOSURE)
            HillviewLogging.logger().error("Close connection for client: {}, {}",
                                         session.getId(), reason.toString());
        else
            HillviewLogging.logger().info("Normal connection closing for client: {}",
                                        session.getId());
        RpcObjectManager.instance.removeSession(session);
    }

    @SuppressWarnings("unused")
    @OnError
    public void onError(final Throwable exception, final Session unused) {
        HillviewLogging.logger().error("Exception", exception);
    }
}
