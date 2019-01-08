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

import io.servicetalk.buffer.api.BufferAllocator;
import io.servicetalk.client.api.ClientGroup;
import io.servicetalk.client.api.ConnectionFactoryFilter;
import io.servicetalk.client.api.LoadBalancerFactory;
import io.servicetalk.client.api.ServiceDiscoverer;
import io.servicetalk.client.api.partition.ClosedPartitionException;
import io.servicetalk.client.api.partition.PartitionAttributes;
import io.servicetalk.client.api.partition.PartitionAttributesBuilder;
import io.servicetalk.client.api.partition.PartitionMapFactory;
import io.servicetalk.client.api.partition.PartitionedServiceDiscovererEvent;
import io.servicetalk.client.api.partition.UnknownPartitionException;
import io.servicetalk.client.internal.DefaultPartitionedClientGroup;
import io.servicetalk.client.internal.DefaultPartitionedClientGroup.PartitionedClientFactory;
import io.servicetalk.client.internal.partition.PowerSetPartitionMapFactory;
import io.servicetalk.concurrent.api.Completable;
import io.servicetalk.concurrent.api.Publisher;
import io.servicetalk.concurrent.api.Single;
import io.servicetalk.http.api.DefaultStreamingHttpRequestResponseFactory;
import io.servicetalk.http.api.HttpClientFilterFactory;
import io.servicetalk.http.api.HttpConnectionFilterFactory;
import io.servicetalk.http.api.HttpExecutionStrategy;
import io.servicetalk.http.api.HttpHeadersFactory;
import io.servicetalk.http.api.HttpRequestMetaData;
import io.servicetalk.http.api.HttpRequestMethod;
import io.servicetalk.http.api.HttpResponseStatus;
import io.servicetalk.http.api.PartitionHttpClientBuilderFilterFunction;
import io.servicetalk.http.api.PartitionedHttpClientBuilder;
import io.servicetalk.http.api.StreamingHttpClient;
import io.servicetalk.http.api.StreamingHttpConnection;
import io.servicetalk.http.api.StreamingHttpRequest;
import io.servicetalk.http.api.StreamingHttpRequestResponseFactory;
import io.servicetalk.http.api.StreamingHttpResponse;
import io.servicetalk.transport.api.ExecutionContext;
import io.servicetalk.transport.api.IoExecutor;
import io.servicetalk.transport.api.SslConfig;

import java.net.SocketOption;
import java.util.function.Function;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static io.servicetalk.concurrent.Cancellable.IGNORE_CANCEL;
import static io.servicetalk.concurrent.api.Completable.completed;
import static io.servicetalk.concurrent.api.Single.error;
import static java.util.Objects.requireNonNull;

class DefaultPartitionedHttpClientBuilder<U, R> implements PartitionedHttpClientBuilder<U, R> {

    private final U address;
    private final Function<HttpRequestMetaData, PartitionAttributesBuilder> partitionAttributesBuilderFactory;
    private final DefaultSingleAddressHttpClientBuilder<U, R> builderTemplate;
    private PartitionHttpClientBuilderFilterFunction<U, R> clientFilterFunction = (__, builder) -> builder;
    private PartitionMapFactory partitionMapFactory = PowerSetPartitionMapFactory.INSTANCE;
    private int serviceDiscoveryMaxQueueSize = 32;

    DefaultPartitionedHttpClientBuilder(
            final U address,
            final DefaultSingleAddressHttpClientBuilder<U, R> builderTemplate,
            final Function<HttpRequestMetaData, PartitionAttributesBuilder> partitionAttributesBuilderFactory) {
        this.address = address;
        this.builderTemplate = requireNonNull(builderTemplate);
        this.partitionAttributesBuilderFactory = requireNonNull(partitionAttributesBuilderFactory);
    }

    @Override
    public StreamingHttpClient buildStreaming() {

        final DefaultSingleAddressHttpClientBuilder<U, R> copy = builderTemplate.copy();

        final ExecutionContext exec = copy.buildExecutionContext();

        final StreamingHttpRequestResponseFactory reqRespFactory =
                new DefaultStreamingHttpRequestResponseFactory(exec.bufferAllocator(),
                        copy.headersFactory());

        PartitionedClientFactory<U, R, StreamingHttpClient>
                clientFactory = (pa, sd) ->
                    clientFilterFunction.apply(pa, copy.copy().serviceDiscoverer(sd))
                            .buildStreaming();

        @SuppressWarnings("unchecked")
        Publisher<? extends PartitionedServiceDiscovererEvent<R>> psdEvents =
                (Publisher<? extends PartitionedServiceDiscovererEvent<R>>) copy.serviceDiscoverer().discover(address);

        return new DefaultPartitionedStreamingHttpClient<>(psdEvents, serviceDiscoveryMaxQueueSize, clientFactory,
                partitionAttributesBuilderFactory, reqRespFactory, exec, partitionMapFactory);
    }

    private static final class DefaultPartitionedStreamingHttpClient<U, R> extends StreamingHttpClient {

        private static final Function<PartitionAttributes, StreamingHttpClient> PARTITION_CLOSED = pa ->
                new NoopPartitionClient(new ClosedPartitionException(pa, "Partition closed "));
        private static final Function<PartitionAttributes, StreamingHttpClient> PARTITION_UNKNOWN = pa ->
                new NoopPartitionClient(new UnknownPartitionException(pa, "Partition unknown"));

        private final ClientGroup<PartitionAttributes, StreamingHttpClient> group;
        private final Function<HttpRequestMetaData, PartitionAttributesBuilder> pabf;
        private final ExecutionContext executionContext;

        DefaultPartitionedStreamingHttpClient(
                final Publisher<? extends PartitionedServiceDiscovererEvent<R>> psdEvents,
                final int psdMaxQueueSize,
                final PartitionedClientFactory<U, R, StreamingHttpClient> clientFactory,
                final Function<HttpRequestMetaData, PartitionAttributesBuilder> pabf,
                final StreamingHttpRequestResponseFactory reqRespFactory,
                final ExecutionContext executionContext,
                final PartitionMapFactory partitionMapFactory) {
            super(reqRespFactory);
            this.pabf = pabf;
            this.executionContext = executionContext;
            this.group = new DefaultPartitionedClientGroup<>(PARTITION_CLOSED, PARTITION_UNKNOWN, clientFactory,
                    partitionMapFactory, psdEvents, psdMaxQueueSize);
        }

        @Override
        public Single<StreamingHttpResponse> request(final HttpExecutionStrategy strategy,
                                                     final StreamingHttpRequest request) {
            return new Single<StreamingHttpResponse>() {
                @Override
                protected void handleSubscribe(final Subscriber<? super StreamingHttpResponse> subscriber) {
                    StreamingHttpClient streamingHttpClient;
                    try {
                        streamingHttpClient = selectClient(request);
                    } catch (Throwable t) {
                        subscriber.onSubscribe(IGNORE_CANCEL);
                        subscriber.onError(t);
                        return;
                    }
                    streamingHttpClient.request(strategy, request).subscribe(subscriber);
                }
            };
        }

        @Nonnull
        private StreamingHttpClient selectClient(final StreamingHttpRequest request) {
            return group.get(pabf.apply(request).build());
        }

        @Override
        public Single<? extends ReservedStreamingHttpConnection> reserveConnection(final HttpExecutionStrategy strategy,
                                                                                   final StreamingHttpRequest request) {
            return new Single<ReservedStreamingHttpConnection>() {
                @Override
                protected void handleSubscribe(final Subscriber<? super ReservedStreamingHttpConnection> subscriber) {
                    StreamingHttpClient streamingHttpClient;
                    try {
                        streamingHttpClient = selectClient(request);
                    } catch (Throwable t) {
                        subscriber.onSubscribe(IGNORE_CANCEL);
                        subscriber.onError(t);
                        return;
                    }
                    streamingHttpClient.reserveConnection(strategy, request).subscribe(subscriber);
                }
            };
        }

        @Override
        public Completable onClose() {
            return group.onClose();
        }

        @Override
        public Completable closeAsync() {
            return group.closeAsync();
        }

        @Override
        public ExecutionContext executionContext() {
            return executionContext;
        }
    }

    private static class NoopPartitionClient extends StreamingHttpClient {
        private final RuntimeException ex;

        NoopPartitionClient(RuntimeException ex) {
            super(new StreamingHttpRequestResponseFactory() {
                @Override
                public StreamingHttpResponse newResponse(final HttpResponseStatus status) {
                    throw ex;
                }

                @Override
                public StreamingHttpRequest newRequest(final HttpRequestMethod method, final String requestTarget) {
                    throw ex;
                }
            });
            this.ex = ex;
        }

        @Override
        public Single<? extends ReservedStreamingHttpConnection> reserveConnection(final HttpExecutionStrategy strategy,
                                                                                   final StreamingHttpRequest request) {
            return error(ex);
        }

        @Override
        public Single<StreamingHttpResponse> request(final HttpExecutionStrategy strategy,
                                                     final StreamingHttpRequest request) {
            return error(ex);
        }

        @Override
        public ExecutionContext executionContext() {
            throw ex;
        }

        @Override
        public Completable onClose() {
            return completed();
        }

        @Override
        public Completable closeAsync() {
            return completed();
        }
    }

    @Override
    public PartitionedHttpClientBuilder<U, R> ioExecutor(final IoExecutor ioExecutor) {
        builderTemplate.ioExecutor(ioExecutor);
        return this;
    }

    @Override
    public PartitionedHttpClientBuilder<U, R> executionStrategy(final HttpExecutionStrategy strategy) {
        builderTemplate.executionStrategy(strategy);
        return this;
    }

    @Override
    public PartitionedHttpClientBuilder<U, R> bufferAllocator(final BufferAllocator allocator) {
        builderTemplate.bufferAllocator(allocator);
        return this;
    }

    @Override
    public <T> PartitionedHttpClientBuilder<U, R> socketOption(final SocketOption<T> option, final T value) {
        builderTemplate.socketOption(option, value);
        return this;
    }

    @Override
    public PartitionedHttpClientBuilder<U, R> enableWireLogging(final String loggerName) {
        builderTemplate.enableWireLogging(loggerName);
        return this;
    }

    @Override
    public PartitionedHttpClientBuilder<U, R> disableWireLogging() {
        builderTemplate.disableWireLogging();
        return this;
    }

    @Override
    public PartitionedHttpClientBuilder<U, R> headersFactory(final HttpHeadersFactory headersFactory) {
        builderTemplate.headersFactory(headersFactory);
        return this;
    }

    @Override
    public PartitionedHttpClientBuilder<U, R> maxInitialLineLength(final int maxInitialLineLength) {
        builderTemplate.maxInitialLineLength(maxInitialLineLength);
        return this;
    }

    @Override
    public PartitionedHttpClientBuilder<U, R> maxHeaderSize(final int maxHeaderSize) {
        builderTemplate.maxHeaderSize(maxHeaderSize);
        return this;
    }

    @Override
    public PartitionedHttpClientBuilder<U, R> headersEncodedSizeEstimate(final int headersEncodedSizeEstimate) {
        builderTemplate.headersEncodedSizeEstimate(headersEncodedSizeEstimate);
        return this;
    }

    @Override
    public PartitionedHttpClientBuilder<U, R> trailersEncodedSizeEstimate(final int trailersEncodedSizeEstimate) {
        builderTemplate.trailersEncodedSizeEstimate(trailersEncodedSizeEstimate);
        return this;
    }

    @Override
    public PartitionedHttpClientBuilder<U, R> maxPipelinedRequests(final int maxPipelinedRequests) {
        builderTemplate.maxPipelinedRequests(maxPipelinedRequests);
        return this;
    }

    @Override
    public PartitionedHttpClientBuilder<U, R> appendConnectionFilter(final HttpConnectionFilterFactory factory) {
        builderTemplate.appendConnectionFilter(factory);
        return this;
    }

    @Override
    public PartitionedHttpClientBuilder<U, R> appendConnectionFactoryFilter(
            final ConnectionFactoryFilter<R, StreamingHttpConnection> factory) {
        builderTemplate.appendConnectionFactoryFilter(factory);
        return this;
    }

    @Override
    public PartitionedHttpClientBuilder<U, R> disableHostHeaderFallback() {
        builderTemplate.disableHostHeaderFallback();
        return this;
    }

    @Override
    public PartitionedHttpClientBuilder<U, R> disableWaitForLoadBalancer() {
        builderTemplate.disableWaitForLoadBalancer();
        return this;
    }

    @Override
    public PartitionedHttpClientBuilder<U, R> serviceDiscoverer(
            final ServiceDiscoverer<U, R, ? extends PartitionedServiceDiscovererEvent<R>> serviceDiscoverer) {
        builderTemplate.serviceDiscoverer(serviceDiscoverer);
        return this;
    }

    @Override
    public PartitionedHttpClientBuilder<U, R> loadBalancerFactory(
            final LoadBalancerFactory<R, StreamingHttpConnection> loadBalancerFactory) {
        builderTemplate.loadBalancerFactory(loadBalancerFactory);
        return this;
    }

    @Override
    public PartitionedHttpClientBuilder<U, R> enableHostHeaderFallback(final CharSequence hostHeader) {
        builderTemplate.enableHostHeaderFallback(hostHeader);
        return this;
    }

    @Override
    public PartitionedHttpClientBuilder<U, R> appendClientFilter(final HttpClientFilterFactory function) {
        builderTemplate.appendClientFilter(function);
        return this;
    }

    @Override
    public PartitionedHttpClientBuilder<U, R> sslConfig(@Nullable final SslConfig sslConfig) {
        builderTemplate.sslConfig(sslConfig);
        return this;
    }

    @Override
    public PartitionedHttpClientBuilder<U, R> serviceDiscoveryMaxQueueSize(final int serviceDiscoveryMaxQueueSize) {
        this.serviceDiscoveryMaxQueueSize = serviceDiscoveryMaxQueueSize;
        return this;
    }

    @Override
    public PartitionedHttpClientBuilder<U, R> partitionMapFactory(final PartitionMapFactory partitionMapFactory) {
        this.partitionMapFactory = partitionMapFactory;
        return this;
    }

    @Override
    public PartitionedHttpClientBuilder<U, R> appendClientBuilderFilter(
            final PartitionHttpClientBuilderFilterFunction<U, R> clientFilterFunction) {
        this.clientFilterFunction = this.clientFilterFunction.append(clientFilterFunction);
        return this;
    }
}