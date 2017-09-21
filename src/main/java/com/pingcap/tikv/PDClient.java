/*
 * Copyright 2017 PingCAP, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.pingcap.tikv;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.net.HostAndPort;
import com.google.protobuf.ByteString;
import com.pingcap.tikv.codec.CodecDataOutput;
import com.pingcap.tikv.exception.GrpcException;
import com.pingcap.tikv.exception.TiClientInternalException;
import com.pingcap.tikv.kvproto.Kvrpcpb.IsolationLevel;
import com.pingcap.tikv.kvproto.Metapb.Store;
import com.pingcap.tikv.kvproto.PDGrpc;
import com.pingcap.tikv.kvproto.PDGrpc.PDBlockingStub;
import com.pingcap.tikv.kvproto.PDGrpc.PDStub;
import com.pingcap.tikv.kvproto.Pdpb.GetMembersRequest;
import com.pingcap.tikv.kvproto.Pdpb.GetMembersResponse;
import com.pingcap.tikv.kvproto.Pdpb.GetRegionByIDRequest;
import com.pingcap.tikv.kvproto.Pdpb.GetRegionRequest;
import com.pingcap.tikv.kvproto.Pdpb.GetRegionResponse;
import com.pingcap.tikv.kvproto.Pdpb.GetStoreRequest;
import com.pingcap.tikv.kvproto.Pdpb.GetStoreResponse;
import com.pingcap.tikv.kvproto.Pdpb.Member;
import com.pingcap.tikv.kvproto.Pdpb.RequestHeader;
import com.pingcap.tikv.kvproto.Pdpb.Timestamp;
import com.pingcap.tikv.kvproto.Pdpb.TsoRequest;
import com.pingcap.tikv.kvproto.Pdpb.TsoResponse;
import com.pingcap.tikv.meta.TiTimestamp;
import com.pingcap.tikv.operation.PDErrorHandler;
import com.pingcap.tikv.region.TiRegion;
import com.pingcap.tikv.types.BytesType;
import com.pingcap.tikv.util.FutureObserver;
import io.grpc.ManagedChannel;
import io.grpc.stub.StreamObserver;
import java.net.URL;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public class PDClient extends AbstractGRPCClient<PDBlockingStub, PDStub>
    implements ReadOnlyPDClient {
  private RequestHeader header;
  private TsoRequest tsoReq;
  private volatile LeaderWrapper leaderWrapper;
  private ScheduledExecutorService service;
  private IsolationLevel isolationLevel;


  @Override
  public TiTimestamp getTimestamp() {
    FutureObserver<Timestamp, TsoResponse> responseObserver =
        new FutureObserver<>(TsoResponse::getTimestamp);
    StreamObserver<TsoRequest> requestObserver =
        callBidiStreamingWithRetry(PDGrpc.METHOD_TSO, responseObserver, null);
    requestObserver.onNext(tsoReq);
    requestObserver.onCompleted();
    try {
      Timestamp timestamp = responseObserver.getFuture().get();
      return new TiTimestamp(timestamp.getPhysical(), timestamp.getLogical());
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
    } catch (Exception e) {
      throw new GrpcException(e);
    }
    return null;
  }

  @Override
  public TiRegion getRegionByKey(ByteString key) {
    CodecDataOutput cdo = new CodecDataOutput();
    BytesType.writeBytes(cdo, key.toByteArray());
    ByteString encodedKey = cdo.toByteString();

    Supplier<GetRegionRequest> request = () ->
        GetRegionRequest.newBuilder().setHeader(header).setRegionKey(encodedKey).build();

    PDErrorHandler<GetRegionResponse> handler =
        new PDErrorHandler<>(r -> r.getHeader().hasError() ? r.getHeader().getError() : null, this);

    GetRegionResponse resp = callWithRetry(PDGrpc.METHOD_GET_REGION, request, handler);
    return new TiRegion(resp.getRegion(), resp.getLeader(), isolationLevel);
  }

  @Override
  public Future<TiRegion> getRegionByKeyAsync(ByteString key) {
    FutureObserver<TiRegion, GetRegionResponse> responseObserver =
        new FutureObserver<>(resp -> new TiRegion(resp.getRegion(), resp.getLeader(), isolationLevel));
    Supplier<GetRegionRequest> request = () ->
        GetRegionRequest.newBuilder().setHeader(header).setRegionKey(key).build();

    PDErrorHandler<GetRegionResponse> handler =
        new PDErrorHandler<>(r -> r.getHeader().hasError() ? r.getHeader().getError() : null, this);
    callAsyncWithRetry(PDGrpc.METHOD_GET_REGION, request, responseObserver, handler);
    return responseObserver.getFuture();
  }

  @Override
  public TiRegion getRegionByID(long id) {
    Supplier<GetRegionByIDRequest> request = () ->
        GetRegionByIDRequest.newBuilder().setHeader(header).setRegionId(id).build();
    PDErrorHandler<GetRegionResponse> handler =
        new PDErrorHandler<>(r -> r.getHeader().hasError() ? r.getHeader().getError() : null, this);
    GetRegionResponse resp = callWithRetry(PDGrpc.METHOD_GET_REGION_BY_ID, request, handler);
    // Instead of using default leader instance, explicitly set no leader to null
    return new TiRegion(resp.getRegion(), resp.getLeader(), isolationLevel);
  }

  /**
   * Change default read committed to other isolation level.
   * @param level is a enum which indicates isolation level.
   */
  public void setIsolationLevel(IsolationLevel level) {
    this.isolationLevel = level;
  }

  @Override
  public Future<TiRegion> getRegionByIDAsync(long id) {
    FutureObserver<TiRegion, GetRegionResponse> responseObserver =
        new FutureObserver<>(resp -> new TiRegion(resp.getRegion(), resp.getLeader(), isolationLevel));

    Supplier<GetRegionByIDRequest> request = () ->
        GetRegionByIDRequest.newBuilder().setHeader(header).setRegionId(id).build();
    PDErrorHandler<GetRegionResponse> handler =
        new PDErrorHandler<>(r -> r.getHeader().hasError() ? r.getHeader().getError() : null, this);
    callAsyncWithRetry(PDGrpc.METHOD_GET_REGION_BY_ID, request, responseObserver, handler);
    return responseObserver.getFuture();
  }

  @Override
  public Store getStore(long storeId) {
    Supplier<GetStoreRequest> request = () ->
        GetStoreRequest.newBuilder().setHeader(header).setStoreId(storeId).build();
    PDErrorHandler<GetStoreResponse> handler =
        new PDErrorHandler<>(r -> r.getHeader().hasError() ? r.getHeader().getError() : null, this);
    GetStoreResponse resp = callWithRetry(PDGrpc.METHOD_GET_STORE, request, handler);
    return resp.getStore();
  }

  @Override
  public Future<Store> getStoreAsync(long storeId) {
    FutureObserver<Store, GetStoreResponse> responseObserver =
        new FutureObserver<>((GetStoreResponse resp) -> resp.getStore());

    Supplier<GetStoreRequest> request = () ->
        GetStoreRequest.newBuilder().setHeader(header).setStoreId(storeId).build();
    PDErrorHandler<GetStoreResponse> handler =
        new PDErrorHandler<>(r -> r.getHeader().hasError() ? r.getHeader().getError() : null, this);
    callAsyncWithRetry(PDGrpc.METHOD_GET_STORE, request, responseObserver, handler);
    return responseObserver.getFuture();
  }

  @Override
  public void close() throws InterruptedException {
    if (service != null) {
      service.shutdownNow();
    }
    if (getLeaderWrapper() != null) {
      getLeaderWrapper().close();
    }
  }

  public static ReadOnlyPDClient create(TiSession session) {
    return createRaw(session);
  }

  @VisibleForTesting
  RequestHeader getHeader() {
    return header;
  }

  @VisibleForTesting
  LeaderWrapper getLeaderWrapper() {
    return leaderWrapper;
  }

  class LeaderWrapper {
    private final String leaderInfo;
    private final PDBlockingStub blockingStub;
    private final PDStub asyncStub;
    private final long createTime;

    LeaderWrapper(
        String leaderInfo,
        PDGrpc.PDBlockingStub blockingStub,
        PDGrpc.PDStub asyncStub,
        long createTime) {
      this.leaderInfo = leaderInfo;
      this.blockingStub = blockingStub;
      this.asyncStub = asyncStub;
      this.createTime = createTime;
    }

    String getLeaderInfo() {
      return leaderInfo;
    }

    PDBlockingStub getBlockingStub() {
      return blockingStub;
    }

    PDStub getAsyncStub() {
      return asyncStub;
    }

    long getCreateTime() {
      return createTime;
    }

    void close() {
    }
  }

  public GetMembersResponse getMembers() {
    List<HostAndPort> pdAddrs = getConf().getPdAddrs();
    checkArgument(pdAddrs.size() > 0, "No PD address specified.");
    for (HostAndPort url : pdAddrs) {
      try {
        ManagedChannel probChan = getSession().getChannel(url.getHostText() + ":" + url.getPort());
        PDGrpc.PDBlockingStub stub = PDGrpc.newBlockingStub(probChan);
        GetMembersRequest request =
            GetMembersRequest.newBuilder().setHeader(RequestHeader.getDefaultInstance()).build();
        return stub.getMembers(request);
      } catch (Exception ignore) {}
    }
    return null;
  }

  public void updateLeader(GetMembersResponse resp) {
    String leaderUrlStr = "URL Not Set";
    try {
      long ts = System.nanoTime();
      synchronized (this) {
        // Lock for not flooding during pd error
        if (leaderWrapper != null && leaderWrapper.getCreateTime() > ts) return;

        if (resp == null) {
          resp = getMembers();
          if (resp == null) return;
        }
        Member leader = resp.getLeader();
        List<String> leaderUrls = leader.getClientUrlsList();
        if (leaderUrls.isEmpty()) return;
        leaderUrlStr = leaderUrls.get(0);
        URL tURL = new URL(leaderUrlStr);
        HostAndPort newLeader = HostAndPort.fromParts(tURL.getHost(), tURL.getPort());
        leaderUrlStr = newLeader.toString();
        // TODO: Why not strip protocol info on server side since grpc does not need it
        if (leaderWrapper != null && leaderUrlStr.equals(leaderWrapper.getLeaderInfo())) {
          return;
        }

        // switch leader
        ManagedChannel clientChannel = getSession().getChannel(leaderUrlStr);
        leaderWrapper =
            new LeaderWrapper(
                leaderUrlStr,
                PDGrpc.newBlockingStub(clientChannel),
                PDGrpc.newStub(clientChannel),
                System.nanoTime());
        logger.info(String.format("Switched to new leader: %s", leaderWrapper));
      }
    } catch (Exception e) {
      logger.error("Error updating leader.", e);
    }
  }

  @Override
  protected PDBlockingStub getBlockingStub() {
    return leaderWrapper
        .getBlockingStub()
        .withDeadlineAfter(getConf().getTimeout(), getConf().getTimeoutUnit());
  }

  @Override
  protected PDStub getAsyncStub() {
    return leaderWrapper
        .getAsyncStub()
        .withDeadlineAfter(getConf().getTimeout(), getConf().getTimeoutUnit());
  }

  private PDClient(TiSession session) {
    super(session);
  }

  private void initCluster() {
    GetMembersResponse resp = getMembers();
    checkNotNull(resp, "Failed to init client for PD cluster.");
    long clusterId = resp.getHeader().getClusterId();
    header = RequestHeader.newBuilder().setClusterId(clusterId).build();
    tsoReq = TsoRequest.newBuilder().setHeader(header).build();
    updateLeader(resp);
    if (leaderWrapper == null) {
      throw new TiClientInternalException("Error Updating leader.");
    }
    service = Executors.newSingleThreadScheduledExecutor();
    service.scheduleAtFixedRate(() -> updateLeader(null), 1, 1, TimeUnit.MINUTES);
  }

  static PDClient createRaw(TiSession session) {
    PDClient client = null;
    try {
      client = new PDClient(session);
      client.setIsolationLevel(IsolationLevel.RC);
      client.initCluster();
    } catch (Exception e) {
      if (client != null) {
        try {
          client.close();
        } catch (InterruptedException ignore) {
        }
      }
      throw e;
    }

    return client;
  }
}
