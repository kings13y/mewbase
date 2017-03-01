package io.mewbase.server.impl;

import io.mewbase.server.*;
import io.mewbase.bson.BsonArray;
import io.mewbase.bson.BsonObject;
import io.mewbase.client.Client;
import io.mewbase.common.SubDescriptor;
import io.mewbase.server.Binder;
import io.mewbase.server.Log;
import io.mewbase.server.impl.auth.UnauthorizedUser;
import io.mewbase.server.impl.cqrs.QueryImpl;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.parsetools.RecordParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Created by tim on 23/09/16.
 */
public class ConnectionImpl implements ServerFrameHandler {

    private final static Logger logger = LoggerFactory.getLogger(ConnectionImpl.class);

    private final ServerImpl server;
    private final TransportConnection transportConnection;
    private final Context context;
    private final Map<Integer, SubscriptionImpl> subscriptionMap = new HashMap<>();
    private final Map<Integer, QueryExecution> queryStates = new HashMap<>();

    private boolean closed;
    private MewbaseAuthProvider authProvider;
    private MewbaseUser user;
    private int subSeq;

    public ConnectionImpl(ServerImpl server, TransportConnection transportConnection, Context context,
                          MewbaseAuthProvider authProvider) {
        Protocol protocol = new Protocol(this);
        RecordParser recordParser = protocol.recordParser();
        transportConnection.handler(recordParser::handle);
        this.server = server;
        this.transportConnection = transportConnection;
        this.context = context;
        this.authProvider = authProvider;
        transportConnection.closeHandler(this::close);
    }

    @Override
    public void handleConnect(BsonObject frame) {
        checkContext();

        BsonObject value = (BsonObject)frame.getValue(Protocol.CONNECT_AUTH_INFO);
        CompletableFuture<MewbaseUser> cf = authProvider.authenticate(value);

        cf.handle((result, ex) -> {

            checkContext();
            BsonObject response = new BsonObject();
            if (ex != null) {
                sendErrorResponse(Client.ERR_AUTHENTICATION_FAILED, "Authentication failed");
                logAndClose(ex.getMessage());
            } else {
                if (result != null) {
                    user = result;
                    response.put(Protocol.RESPONSE_OK, true);
                    writeResponse(Protocol.RESPONSE_FRAME, response);
                } else {
                    String nullUserMsg = "AuthProvider returned a null user";
                    logAndClose(nullUserMsg);
                    throw new IllegalStateException(nullUserMsg);
                }
            }
            return null;
        });
        // TODO version checking
    }

    @Override
    public void handlePublish(BsonObject frame) {
        checkContext();

        CompletableFuture<Boolean> authorisedCF = user.isAuthorised(Protocol.PUBLISH_FRAME);

        Consumer<BsonObject> frameConsumer = (protocolFrame) -> {
            String channel = protocolFrame.getString(Protocol.PUBLISH_CHANNEL);
            BsonObject event = protocolFrame.getBsonObject(Protocol.PUBLISH_EVENT);
            Integer sessID = protocolFrame.getInteger(Protocol.PUBLISH_SESSID);
            Integer requestID = protocolFrame.getInteger(Protocol.REQUEST_REQUEST_ID);

            if (channel == null) {
                missingField(Protocol.PUBLISH_CHANNEL, Protocol.PUBLISH_FRAME);
                return;
            }
            if (event == null) {
                missingField(Protocol.PUBLISH_EVENT, Protocol.PUBLISH_FRAME);
                return;
            }
            if (requestID == null) {
                missingField(Protocol.REQUEST_REQUEST_ID, Protocol.PUBLISH_FRAME);
                return;
            }
            Log log = server.getLog(channel);
            if (log == null) {
                sendErrorResponse(Client.ERR_NO_SUCH_CHANNEL, "no such channel " + channel, requestID);
                return;
            }
            CompletableFuture<Long> cf = server.publishEvent(log, event);

            cf.handle((v, ex) -> {
                if (ex == null) {
                    BsonObject resp = new BsonObject();
                    resp.put(Protocol.RESPONSE_REQUEST_ID, requestID);
                    resp.put(Protocol.RESPONSE_OK, true);
                    writeResponse(Protocol.RESPONSE_FRAME, resp);
                } else {
                    sendErrorResponse(Client.ERR_SERVER_ERROR, "failed to persist", requestID);
                }
                return null;
            });
        };

        authorisedCF.handle((res, ex) -> {
            if (ex != null) {
                sendErrorResponse(Client.ERR_AUTHORISATION_FAILED, "Authorisation failed", frame.getInteger(Protocol.REQUEST_REQUEST_ID));
                logAndClose(ex.getMessage());
            }
            handleFrame(frame, frameConsumer, res);
            return null;
        });
    }

    @Override
    public void handleStartTx(BsonObject frame) {
        checkContext();

        CompletableFuture<Boolean> authorisedCF = user.isAuthorised(Protocol.STARTTX_FRAME);

        authorisedCF.handle((res, ex) -> null);
    }

    @Override
    public void handleCommitTx(BsonObject frame) {
        checkContext();

        CompletableFuture<Boolean> authorisedCF = user.isAuthorised(Protocol.COMMITTX_FRAME);

        authorisedCF.handle((res, ex) -> null);
    }

    @Override
    public void handleAbortTx(BsonObject frame) {
        checkContext();

        CompletableFuture<Boolean> authorisedCF = user.isAuthorised(Protocol.ABORTTX_FRAME);

        authorisedCF.handle((res, ex) -> null);
    }

    @Override
    public void handleSubscribe(BsonObject frame) {
        checkContext();

        CompletableFuture<Boolean> authorisedCF = user.isAuthorised(Protocol.SUBSCRIBE_FRAME);

        Consumer<BsonObject> frameConsumer = (protocolFrame) -> {
            String channel = protocolFrame.getString(Protocol.SUBSCRIBE_CHANNEL);

            if (channel == null) {
                missingField(Protocol.SUBSCRIBE_CHANNEL, Protocol.SUBSCRIBE_FRAME);
            }

            Integer requestID = protocolFrame.getInteger(Protocol.REQUEST_REQUEST_ID);
            if (requestID == null) {
                missingField(Protocol.REQUEST_REQUEST_ID, Protocol.SUBSCRIBE_FRAME);
            }

            Long startSeq = protocolFrame.getLong(Protocol.SUBSCRIBE_STARTPOS);
            Long startTimestamp = protocolFrame.getLong(Protocol.SUBSCRIBE_STARTTIMESTAMP);
            String durableID = protocolFrame.getString(Protocol.SUBSCRIBE_DURABLEID);
            BsonObject matcher = protocolFrame.getBsonObject(Protocol.SUBSCRIBE_MATCHER);
            SubDescriptor subDescriptor = new SubDescriptor().setStartPos(startSeq == null ? -1 : startSeq).setStartTimestamp(startTimestamp)
                    .setMatcher(matcher).setDurableID(durableID).setChannel(channel);
            int subID = subSeq++;
            checkWrap(subSeq);
            Log log = server.getLog(channel);
            if (log == null) {
                sendErrorResponse(Client.ERR_NO_SUCH_CHANNEL, "no such channel " + channel, requestID);
            }
            SubscriptionImpl subscription = new SubscriptionImpl(this, subID, subDescriptor);
            subscriptionMap.put(subID, subscription);
            BsonObject resp = new BsonObject();
            resp.put(Protocol.RESPONSE_REQUEST_ID, requestID);
            resp.put(Protocol.RESPONSE_OK, true);
            resp.put(Protocol.SUBRESPONSE_SUBID, subID);
            writeResponse(Protocol.SUBRESPONSE_FRAME, resp);
            logger.trace("Subscribed channel: {} startSeq {}", channel, startSeq);
        };

        authorisedCF.handle((res, ex) -> {
            if (ex != null) {
                sendErrorResponse(Client.ERR_AUTHORISATION_FAILED, "Authorisation failed", frame.getInteger(Protocol.REQUEST_REQUEST_ID));
                logAndClose(ex.getMessage());
            }
            handleFrame(frame, frameConsumer, res);
            return null;
        });

    }

    @Override
    public void handleSubClose(BsonObject frame) {
        checkContext();

        CompletableFuture<Boolean> authorisedCF = user.isAuthorised(Protocol.SUBCLOSE_FRAME);

        Consumer<BsonObject> frameConsumer = (protocolFrame) -> {
            Integer subID = protocolFrame.getInteger(Protocol.SUBCLOSE_SUBID);
            if (subID == null) {
                missingField(Protocol.SUBCLOSE_SUBID, Protocol.SUBCLOSE_FRAME);
                return;
            }
            Integer requestID = protocolFrame.getInteger(Protocol.REQUEST_REQUEST_ID);
            if (requestID == null) {
                missingField(Protocol.REQUEST_REQUEST_ID, Protocol.SUBCLOSE_FRAME);
                return;
            }
            SubscriptionImpl subscription = subscriptionMap.remove(subID);
            if (subscription == null) {
                invalidField(Protocol.SUBCLOSE_SUBID, Protocol.SUBCLOSE_FRAME);
                return;
            }
            subscription.close();
            BsonObject resp = new BsonObject();
            resp.put(Protocol.RESPONSE_OK, true);
            resp.put(Protocol.RESPONSE_REQUEST_ID, requestID);
            writeResponse(Protocol.RESPONSE_FRAME, resp);
        };

        authorisedCF.handle((res, ex) -> {
            if (ex != null) {
                sendErrorResponse(Client.ERR_AUTHORISATION_FAILED, "Authorisation failed", frame.getInteger(Protocol.REQUEST_REQUEST_ID));
                logAndClose(ex.getMessage());
            }
            handleFrame(frame, frameConsumer, res);
            return null;
        });
    }

    @Override
    public void handleUnsubscribe(BsonObject frame) {
        checkContext();

        CompletableFuture<Boolean> authorisedCF = user.isAuthorised(Protocol.UNSUBSCRIBE_FRAME);

        Consumer<BsonObject> frameConsumer = (protocolFrame) -> {

            Integer subID = protocolFrame.getInteger(Protocol.UNSUBSCRIBE_SUBID);
            if (subID == null) {
                missingField(Protocol.UNSUBSCRIBE_SUBID, Protocol.UNSUBSCRIBE_FRAME);
                return;
            }
            Integer requestID = protocolFrame.getInteger(Protocol.REQUEST_REQUEST_ID);
            if (requestID == null) {
                missingField(Protocol.REQUEST_REQUEST_ID, Protocol.UNSUBSCRIBE_FRAME);
                return;
            }
            SubscriptionImpl subscription = subscriptionMap.remove(subID);
            if (subscription == null) {
                invalidField(Protocol.UNSUBSCRIBE_SUBID, Protocol.UNSUBSCRIBE_FRAME);
                return;
            }
            subscription.close();
            subscription.unsubscribe();
            BsonObject resp = new BsonObject();
            resp.put(Protocol.RESPONSE_OK, true);
            resp.put(Protocol.RESPONSE_REQUEST_ID, requestID);
            writeResponse(Protocol.RESPONSE_FRAME, resp);
        };

        authorisedCF.handle((res, ex) -> {
            if (ex != null) {
                sendErrorResponse(Client.ERR_AUTHORISATION_FAILED, "Authorisation failed", frame.getInteger(Protocol.REQUEST_REQUEST_ID));
                logAndClose(ex.getMessage());
            }
            handleFrame(frame, frameConsumer, res);
            return null;
        });
    }


    @Override
    public void handleAckEv(BsonObject frame) {
        checkContext();
        CompletableFuture<Boolean> authorisedCF = user.isAuthorised(Protocol.ACKEV_FRAME);

        Consumer<BsonObject> frameConsumer = (protocolFrame) -> {
            Integer subID = protocolFrame.getInteger(Protocol.ACKEV_SUBID);
            if (subID == null) {
                missingField(Protocol.ACKEV_SUBID, Protocol.ACKEV_FRAME);
                return;
            }
            Integer bytes = protocolFrame.getInteger(Protocol.ACKEV_BYTES);
            if (bytes == null) {
                missingField(Protocol.ACKEV_BYTES, Protocol.ACKEV_FRAME);
                return;
            }
            Long pos = protocolFrame.getLong(Protocol.ACKEV_POS);
            if (pos == null) {
                missingField(Protocol.ACKEV_POS, Protocol.ACKEV_FRAME);
                return;
            }
            SubscriptionImpl subscription = subscriptionMap.get(subID);
            if (subscription == null) {
                invalidField(Protocol.ACKEV_SUBID, Protocol.ACKEV_FRAME);
                return;
            }
            subscription.handleAckEv(pos, bytes);
        };

        authorisedCF.handle((res, ex) -> {
            if (ex != null) {
                sendErrorResponse(Client.ERR_AUTHORISATION_FAILED, "Authorisation failed", frame.getInteger(Protocol.REQUEST_REQUEST_ID));
                logAndClose(ex.getMessage());
            }
            handleFrame(frame, frameConsumer, res);
            return null;
        });
    }

    @Override
    public void handleQuery(BsonObject frame) {
        checkContext();

        CompletableFuture<Boolean> authorisedCF = user.isAuthorised(Protocol.QUERY_FRAME);

        Consumer<BsonObject> frameConsumer = (protocolFrame) -> {
            Integer queryID = frame.getInteger(Protocol.QUERY_QUERYID);
            if (queryID == null) {
                missingField(Protocol.QUERY_QUERYID, Protocol.QUERY_FRAME);
                return;
            }
            String queryName = frame.getString(Protocol.QUERY_NAME);
            if (queryName == null) {
                missingField(Protocol.QUERY_NAME, Protocol.QUERY_FRAME);
                return;
            }
            BsonObject params = frame.getBsonObject(Protocol.QUERY_PARAMS);
            if (params == null) {
                missingField(Protocol.QUERY_PARAMS, Protocol.QUERY_FRAME);
                return;
            }
            QueryImpl query = server.getCqrsManager().getQuery(queryName);
            if (query == null) {
                writeQueryError(Client.ERR_NO_SUCH_QUERY, "No such query " + queryName, queryID);
            } else {
                QueryExecution qe = new ConnectionQueryExecution(this, queryID, query, params);
                queryStates.put(queryID, qe);
                qe.start();
            }
        };

        authorisedCF.handle((res, ex) -> {
            if (ex != null) {
                sendErrorResponse(Client.ERR_AUTHORISATION_FAILED, "Authorisation failed", frame.getInteger(Protocol.REQUEST_REQUEST_ID));
                logAndClose(ex.getMessage());
            }
            handleFrame(frame, frameConsumer, res);
            return null;
        });

    }


    @Override
    public void handleFindByID(BsonObject frame) {
        checkContext();

        CompletableFuture<Boolean> authorisedCF = user.isAuthorised(Protocol.QUERY_FRAME);

        Consumer<BsonObject> frameConsumer = (protocolFrame) -> {
            Integer requestID = frame.getInteger(Protocol.REQUEST_REQUEST_ID);
            if (requestID == null) {
                missingField(Protocol.REQUEST_REQUEST_ID, Protocol.FINDBYID_FRAME);
                return;
            }
            String docID = frame.getString(Protocol.FINDBYID_DOCID);
            if (docID == null) {
                missingField(Protocol.FINDBYID_DOCID, Protocol.FINDBYID_FRAME);
                return;
            }
            String binderName = frame.getString(Protocol.FINDBYID_BINDER);
            if (binderName == null) {
                missingField(Protocol.FINDBYID_BINDER, Protocol.FINDBYID_FRAME);
                return;
            }
            Binder binder = server.getBinder(binderName);
            if (binder != null) {
                CompletableFuture<BsonObject> cf = binder.get(docID);
                cf.thenAccept(doc -> {
                    BsonObject resp = new BsonObject();
                    resp.put(Protocol.RESPONSE_OK, true);
                    resp.put(Protocol.RESPONSE_REQUEST_ID, requestID);
                    resp.put(Protocol.FINDRESPONSE_RESULT, doc);
                    writeResponse(Protocol.RESPONSE_FRAME, resp);
                });
            } else {
                sendErrorResponse(Client.ERR_NO_SUCH_BINDER, "No such binder " + binderName, requestID);
            }
        };

        authorisedCF.handle((res, ex) -> {
            if (ex != null) {
                sendErrorResponse(Client.ERR_AUTHORISATION_FAILED, "Authorisation failed", frame.getInteger(Protocol.REQUEST_REQUEST_ID));
                logAndClose(ex.getMessage());
            }
            handleFrame(frame, frameConsumer, res);
            return null;
        });
    }

    @Override
    public void handleQueryAck(BsonObject frame) {
        checkContext();

        CompletableFuture<Boolean> authorisedCF = user.isAuthorised(Protocol.QUERYACK_FRAME);

        Consumer<BsonObject> frameConsumer = (protocolFrame) -> {
            Integer queryID = protocolFrame.getInteger(Protocol.QUERYACK_QUERYID);
            if (queryID == null) {
                missingField(Protocol.QUERYACK_QUERYID, Protocol.QUERYACK_FRAME);
                return;
            }
            Integer bytes = protocolFrame.getInteger(Protocol.QUERYACK_BYTES);
            if (bytes == null) {
                missingField(Protocol.QUERYACK_BYTES, Protocol.QUERYACK_FRAME);
                return;
            }
            QueryExecution queryState = queryStates.get(queryID);
            if (queryState != null) {
                queryState.handleAck(bytes);
            }
        };

        authorisedCF.handle((res, ex) -> {
            if (ex != null) {
                sendErrorResponse(Client.ERR_AUTHORISATION_FAILED, "Authorisation failed");
                logAndClose(ex.getMessage());
            }
            handleFrame(frame, frameConsumer, res);
            return null;
        });

    }

    @Override
    public void handlePing(BsonObject frame) {
        checkContext();

        CompletableFuture<Boolean> authorisedCF = user.isAuthorised(Protocol.PING_FRAME);

        authorisedCF.handle((res, ex) -> null);
    }

    @Override
    public void handleCommand(BsonObject frame) {
        checkContext();
        CompletableFuture<Boolean> authorisedCF = user.isAuthorised(Protocol.COMMAND_FRAME);

        Consumer<BsonObject> frameConsumer = (protocolFrame) -> {
            String commandName = frame.getString(Protocol.COMMAND_NAME);
            if (commandName == null) {
                missingField(Protocol.COMMAND_NAME, Protocol.COMMAND_FRAME);
                return;
            }
            BsonObject command = frame.getBsonObject(Protocol.COMMAND_COMMAND);
            if (command == null) {
                missingField(Protocol.COMMAND_COMMAND, Protocol.COMMAND_FRAME);
                return;
            }
            Integer requestID = frame.getInteger(Protocol.REQUEST_REQUEST_ID);
            if (requestID == null) {
                missingField(Protocol.REQUEST_REQUEST_ID, Protocol.UNSUBSCRIBE_FRAME);
                return;
            }
            CompletableFuture<Void> cf = server.getCqrsManager().callCommandHandler(commandName, command);
            cf.handle((res, t) -> {
                if (t != null) {
                    // TODO what error to send?
                    //sendErrorResponse(Client.ERR_SERVER_ERROR, "failed to create binder", requestID);
                } else {
                    BsonObject resp = new BsonObject();
                    resp.put(Protocol.RESPONSE_REQUEST_ID, requestID);
                    resp.put(Protocol.RESPONSE_OK, true);
                    writeResponse(Protocol.RESPONSE_FRAME, resp);
                }
                return null;
            });
        };

        authorisedCF.handle((res, ex) -> {
            if (ex != null) {
                sendErrorResponse(Client.ERR_AUTHORISATION_FAILED, "Authorisation failed");
                logAndClose(ex.getMessage());
            }
            handleFrame(frame, frameConsumer, res);
            return null;
        });

    }

    // Admin operations

    @Override
    public void handleListBinders(BsonObject frame) {
        checkContext();

        CompletableFuture<Boolean> authorisedCF = user.isAuthorised(Protocol.LIST_BINDERS_FRAME);

        Consumer<BsonObject> frameConsumer = (protocolFrame) -> {
            Integer requestID = protocolFrame.getInteger(Protocol.REQUEST_REQUEST_ID);
            if (requestID == null) {
                missingField(Protocol.REQUEST_REQUEST_ID, Protocol.LIST_BINDERS_FRAME);
                return;
            }
            BsonObject resp = new BsonObject();
            resp.put(Protocol.RESPONSE_REQUEST_ID, requestID);
            resp.put(Protocol.RESPONSE_OK, true);
            BsonArray arr = new BsonArray(server.listBinders());
            resp.put(Protocol.LISTBINDERS_BINDERS, arr);
            writeResponse(Protocol.RESPONSE_FRAME, resp);
        };

        authorisedCF.handle((res, ex) -> {
            if (ex != null) {
                sendErrorResponse(Client.ERR_AUTHORISATION_FAILED, "Authorisation failed", frame.getInteger(Protocol.REQUEST_REQUEST_ID));
                logAndClose(ex.getMessage());
            }
            handleFrame(frame, frameConsumer, res);
            return null;
        });
    }

    @Override
    public void handleCreateBinder(BsonObject frame) {
        checkContext();

        CompletableFuture<Boolean> authorisedCF = user.isAuthorised(Protocol.CREATEBINDER_NAME);

        Consumer<BsonObject> frameConsumer = (protocolFrame) -> {
            Integer requestID = protocolFrame.getInteger(Protocol.REQUEST_REQUEST_ID);
            if (requestID == null) {
                missingField(Protocol.REQUEST_REQUEST_ID, Protocol.CREATE_BINDER_FRAME);
                return;
            }
            String binderName = protocolFrame.getString(Protocol.CREATEBINDER_NAME);
            if (binderName == null) {
                missingField(Protocol.CREATEBINDER_NAME, Protocol.CREATE_BINDER_FRAME);
                return;
            }
            CompletableFuture<Boolean> cf = server.createBinder(binderName);
            cf.handle((res, t) -> {
                if (t != null) {
                    sendErrorResponse(Client.ERR_SERVER_ERROR, "failed to create binder", requestID);
                } else {
                    BsonObject resp = new BsonObject();
                    resp.put(Protocol.RESPONSE_REQUEST_ID, requestID);
                    resp.put(Protocol.RESPONSE_OK, true);
                    resp.put(Protocol.CREATEBINDER_RESPONSE_EXISTS, !res);
                    writeResponse(Protocol.RESPONSE_FRAME, resp);
                }
                return null;
            });
        };

        authorisedCF.handle((res, ex) -> {
            if (ex != null) {
                sendErrorResponse(Client.ERR_AUTHORISATION_FAILED, "Authorisation failed", frame.getInteger(Protocol.REQUEST_REQUEST_ID));
                logAndClose(ex.getMessage());
            }
            handleFrame(frame, frameConsumer, res);
            return null;
        });
    }

    @Override
    public void handleListChannels(BsonObject frame) {
        checkContext();

        CompletableFuture<Boolean> authorisedCF = user.isAuthorised(Protocol.LIST_CHANNELS_FRAME);

        Consumer<BsonObject> frameConsumer = (protocolFrame) -> {
            Integer requestID = protocolFrame.getInteger(Protocol.REQUEST_REQUEST_ID);
            if (requestID == null) {
                missingField(Protocol.REQUEST_REQUEST_ID, Protocol.LIST_CHANNELS_FRAME);
                return;
            }
            BsonObject resp = new BsonObject();
            resp.put(Protocol.RESPONSE_REQUEST_ID, requestID);
            resp.put(Protocol.RESPONSE_OK, true);
            BsonArray arr = new BsonArray(server.listChannels());
            resp.put(Protocol.LISTCHANNELS_CHANNELS, arr);
            writeResponse(Protocol.RESPONSE_FRAME, resp);
        };

        authorisedCF.handle((res, ex) -> {
            if (ex != null) {
                sendErrorResponse(Client.ERR_AUTHORISATION_FAILED, "Authorisation failed", frame.getInteger(Protocol.REQUEST_REQUEST_ID));
                logAndClose(ex.getMessage());
            }
            handleFrame(frame, frameConsumer, res);
            return null;
        });
    }

    @Override
    public void handleCreateChannel(BsonObject frame) {
        checkContext();

        CompletableFuture<Boolean> authorisedCF = user.isAuthorised(Protocol.CREATECHANNEL_NAME);

        Consumer<BsonObject> frameConsumer = (protocolFrame) -> {
            Integer requestID = protocolFrame.getInteger(Protocol.REQUEST_REQUEST_ID);
            if (requestID == null) {
                missingField(Protocol.REQUEST_REQUEST_ID, Protocol.CREATE_CHANNEL_FRAME);
                return;
            }
            String channelName = protocolFrame.getString(Protocol.CREATECHANNEL_NAME);
            if (channelName == null) {
                missingField(Protocol.CREATECHANNEL_NAME, Protocol.CREATE_CHANNEL_FRAME);
                return;
            }
            CompletableFuture<Boolean> cf = server.createChannel(channelName);
            cf.handle((res, t) -> {
                if (t != null) {
                    sendErrorResponse(Client.ERR_SERVER_ERROR, "failed to create channel", requestID);
                } else {
                    BsonObject resp = new BsonObject();
                    resp.put(Protocol.RESPONSE_REQUEST_ID, requestID);
                    resp.put(Protocol.RESPONSE_OK, true);
                    resp.put(Protocol.CREATECHANNEL_RESPONSE_EXISTS, !res);
                    writeResponse(Protocol.RESPONSE_FRAME, resp);
                }
                return null;
            });
        };

        authorisedCF.handle((res, ex) -> {
            if (ex != null) {
                sendErrorResponse(Client.ERR_AUTHORISATION_FAILED, "Authorisation failed", frame.getInteger(Protocol.REQUEST_REQUEST_ID));
                logAndClose(ex.getMessage());
            }
            handleFrame(frame, frameConsumer, res);
            return null;
        });

    }

    private void handleFrame(BsonObject frame, Consumer<BsonObject> consumer, boolean res) {
        if (res){
            consumer.accept(frame);
        } else {
            sendErrorResponse(Client.ERR_NOT_AUTHORISED, "User is not authorised", frame.getInteger(Protocol.REQUEST_REQUEST_ID));
            logAndClose("User is not authorised");
        }
    }


    protected Buffer writeQueryResult(BsonObject doc, int queryID, boolean last) {
        BsonObject res = new BsonObject();
        res.put(Protocol.QUERYRESULT_OK, true);
        res.put(Protocol.QUERYRESULT_QUERYID, queryID);
        res.put(Protocol.QUERYRESULT_RESULT, doc);
        res.put(Protocol.QUERYRESULT_LAST, last);
        return writeResponse(Protocol.QUERYRESULT_FRAME, res);
    }

    protected Buffer writeQueryError(int errCode, String errMsg, int queryID) {
        BsonObject res = new BsonObject();
        res.put(Protocol.QUERYRESULT_OK, false);
        res.put(Protocol.QUERYRESULT_QUERYID, queryID);
        res.put(Protocol.QUERYRESULT_LAST, true);
        res.put(Protocol.RESPONSE_ERRCODE, errCode);
        res.put(Protocol.RESPONSE_ERRMSG, errMsg);
        return writeResponse(Protocol.QUERYRESULT_FRAME, res);
    }

    protected Buffer writeResponse(String frameName, BsonObject frame) {
        Buffer buff = Protocol.encodeFrame(frameName, frame);
        // TODO compare performance of writing directly in all cases and via context
        Context curr = Vertx.currentContext();
        if (curr != context) {
            context.runOnContext(v -> transportConnection.write(buff));
        } else {
            transportConnection.write(buff);
        }
        return buff;
    }

    protected void checkWrap(int i) {
        // Sanity check - wrap around - won't happen but better to close connection than give incorrect behaviour
        if (i == Integer.MIN_VALUE) {
            String msg = "int wrapped!";
            logger.error(msg);
            close();
        }
    }

    protected void missingField(String fieldName, String frameType) {
        logger.warn("protocol error: missing {} in {}. connection will be closed", fieldName, frameType);
        close();
    }

    protected void invalidField(String fieldName, String frameType) {
        logger.warn("protocol error: invalid {} in {}. connection will be closed", fieldName, frameType);
        close();
    }

    protected void logAndClose(String exceptionMessage) {
        logger.error("{}, Connection will be closed", exceptionMessage);
        close();
    }

    protected void sendErrorResponse(int errCode, String errMsg) {
        sendErrorResponse(errCode, errMsg, null);
    }

    protected void sendErrorResponse(int errCode, String errMsg, Integer requestID) {
        BsonObject resp = new BsonObject();
        if (requestID != null) {
            resp.put(Protocol.RESPONSE_REQUEST_ID, requestID);
        }
        resp.put(Protocol.RESPONSE_OK, false);
        resp.put(Protocol.RESPONSE_ERRCODE, errCode);
        resp.put(Protocol.RESPONSE_ERRMSG, errMsg);
        writeResponse(Protocol.RESPONSE_FRAME, resp);
    }

    // Sanity check - this should always be executed using the correct context
    protected void checkContext() {
        if (Vertx.currentContext() != context) {
            logger.trace("Wrong context!! " + Thread.currentThread() + " expected " + context, new Exception());
            throw new IllegalStateException("Wrong context!");
        }
    }

    protected void removeQueryState(int queryID) {
        checkContext();
        queryStates.remove(queryID);
    }

    protected void close() {
        checkContext();
        if (closed) {
            return;
        }

        user = new UnauthorizedUser();

        for (QueryExecution queryState : queryStates.values()) {
            queryState.close();
        }
        queryStates.clear();
        closed = true;
        transportConnection.close();
    }

    protected ServerImpl server() {
        return server;
    }

}