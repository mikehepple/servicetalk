/*
 * Copyright © 2018 Apple Inc. and the ServiceTalk project authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.servicetalk.http.netty;

import io.servicetalk.concurrent.api.Publisher;
import io.servicetalk.concurrent.api.Single;
import io.servicetalk.http.api.HttpExecutionStrategy;
import io.servicetalk.http.api.HttpProtocolVersion;
import io.servicetalk.http.api.StreamingHttpRequest;
import io.servicetalk.http.api.StreamingHttpRequestResponseFactory;
import io.servicetalk.http.api.StreamingHttpRequester;
import io.servicetalk.http.api.StreamingHttpResponse;
import io.servicetalk.transport.api.ExecutionContext;
import io.servicetalk.transport.netty.internal.DefaultNettyPipelinedConnection;
import io.servicetalk.transport.netty.internal.NettyConnection;

import static io.servicetalk.concurrent.api.Single.error;
import static io.servicetalk.http.api.HttpProtocolVersion.HTTP_1_1;

final class PipelinedStreamingHttpConnectionFilter
        extends AbstractStreamingHttpConnectionFilter<DefaultNettyPipelinedConnection<Object, Object>> {

    PipelinedStreamingHttpConnectionFilter(final NettyConnection<Object, Object> connection,
                                           final ReadOnlyHttpClientConfig config,
                                           final ExecutionContext executionContext,
                                           final StreamingHttpRequestResponseFactory reqRespFactory) {
        super(new DefaultNettyPipelinedConnection<>(connection, config.maxPipelinedRequests()),
                config, executionContext, reqRespFactory);
    }

    @Override
    protected Single<StreamingHttpResponse> request(final StreamingHttpRequester terminalDelegate,
                                                    final HttpExecutionStrategy strategy,
                                                    final StreamingHttpRequest request) {
        HttpProtocolVersion version = request.version();
        if (!HTTP_1_1.equals(version)) {
            return error(new IllegalArgumentException(
                    "Pipelining unsupported in protocol version: " + request.version()));
        }
        return super.request(terminalDelegate, strategy, request);
    }

    @Override
    protected Publisher<Object> writeAndRead(Publisher<Object> requestStream) {
        return connection.request(requestStream);
    }
}