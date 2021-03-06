/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.shardingsphere.shardingproxy.frontend.mysql;

import com.google.common.base.Optional;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import lombok.RequiredArgsConstructor;
import org.apache.shardingsphere.core.constant.properties.ShardingPropertiesConstant;
import org.apache.shardingsphere.core.spi.hook.SPIRootInvokeHook;
import org.apache.shardingsphere.shardingproxy.backend.communication.jdbc.connection.BackendConnection;
import org.apache.shardingsphere.shardingproxy.backend.response.query.QueryHeader;
import org.apache.shardingsphere.shardingproxy.context.GlobalContext;
import org.apache.shardingsphere.shardingproxy.error.CommonErrorCode;
import org.apache.shardingsphere.shardingproxy.transport.common.packet.CommandResponsePackets;
import org.apache.shardingsphere.shardingproxy.transport.common.packet.query.QueryResponsePackets;
import org.apache.shardingsphere.shardingproxy.transport.mysql.packet.MySQLPacket;
import org.apache.shardingsphere.shardingproxy.transport.mysql.packet.MySQLPacketPayload;
import org.apache.shardingsphere.shardingproxy.transport.mysql.packet.command.MySQLCommandPacket;
import org.apache.shardingsphere.shardingproxy.transport.mysql.packet.command.MySQLCommandPacketFactory;
import org.apache.shardingsphere.shardingproxy.transport.mysql.packet.command.query.MySQLColumnDefinition41Packet;
import org.apache.shardingsphere.shardingproxy.transport.mysql.packet.command.query.MySQLFieldCountPacket;
import org.apache.shardingsphere.shardingproxy.transport.mysql.packet.command.query.MySQLQueryCommandPacket;
import org.apache.shardingsphere.shardingproxy.transport.mysql.packet.generic.MySQLEofPacket;
import org.apache.shardingsphere.shardingproxy.transport.mysql.packet.generic.MySQLErrPacket;
import org.apache.shardingsphere.shardingproxy.transport.mysql.packet.generic.MySQLErrPacketFactory;
import org.apache.shardingsphere.shardingproxy.transport.spi.DatabasePacket;
import org.apache.shardingsphere.spi.hook.RootInvokeHook;

import java.sql.SQLException;

/**
 * MySQL command executor.
 *
 * @author zhangyonglun
 * @author zhaojun
 */
@RequiredArgsConstructor
public final class MySQLCommandExecutor implements Runnable {
    
    private final ChannelHandlerContext context;
    
    private final ByteBuf message;
    
    private final BackendConnection backendConnection;
    
    private int currentSequenceId;
    
    private final RootInvokeHook rootInvokeHook = new SPIRootInvokeHook();
    
    @Override
    public void run() {
        rootInvokeHook.start();
        int connectionSize = 0;
        try (MySQLPacketPayload payload = new MySQLPacketPayload(message);
             BackendConnection backendConnection = this.backendConnection) {
            backendConnection.getStateHandler().waitUntilConnectionReleasedIfNecessary();
            MySQLCommandPacket mysqlCommandPacket = getCommandPacket(payload, backendConnection);
            Optional<CommandResponsePackets> responsePackets = mysqlCommandPacket.execute();
            if (!responsePackets.isPresent()) {
                return;
            }
            if (responsePackets.get() instanceof QueryResponsePackets) {
                context.write(new MySQLFieldCountPacket(++currentSequenceId, ((QueryResponsePackets) responsePackets.get()).getQueryHeaders().size()));
                for (QueryHeader each : ((QueryResponsePackets) responsePackets.get()).getQueryHeaders()) {
                    context.write(new MySQLColumnDefinition41Packet(++currentSequenceId, each));
                }
                context.write(new MySQLEofPacket(++currentSequenceId));
                writeMoreResults((MySQLQueryCommandPacket) mysqlCommandPacket, ((QueryResponsePackets) responsePackets.get()).getSequenceId());
            } else {
                for (DatabasePacket each : responsePackets.get().getPackets()) {
                    context.write(each);
                }
            }
            connectionSize = backendConnection.getConnectionSize();
        } catch (final SQLException ex) {
            context.write(MySQLErrPacketFactory.newInstance(++currentSequenceId, ex));
            // CHECKSTYLE:OFF
        } catch (final Exception ex) {
            // CHECKSTYLE:ON
            context.write(new MySQLErrPacket(1, CommonErrorCode.UNKNOWN_EXCEPTION, ex.getMessage()));
        } finally {
            context.flush();
            rootInvokeHook.finish(connectionSize);
        }
    }
    
    private MySQLCommandPacket getCommandPacket(final MySQLPacketPayload payload, final BackendConnection backendConnection) throws SQLException {
        int sequenceId = payload.readInt1();
        return MySQLCommandPacketFactory.newInstance(sequenceId, payload, backendConnection);
    }
    
    private void writeMoreResults(final MySQLQueryCommandPacket mysqlQueryCommandPacket, final int headPacketsCount) throws SQLException {
        if (!context.channel().isActive()) {
            return;
        }
        currentSequenceId = headPacketsCount;
        int count = 0;
        int proxyFrontendFlushThreshold = GlobalContext.getInstance().getShardingProperties().<Integer>getValue(ShardingPropertiesConstant.PROXY_FRONTEND_FLUSH_THRESHOLD);
        while (mysqlQueryCommandPacket.next()) {
            count++;
            while (!context.channel().isWritable() && context.channel().isActive()) {
                context.flush();
                synchronized (backendConnection) {
                    try {
                        backendConnection.wait();
                    } catch (final InterruptedException ignored) {
                    }
                }
            }
            MySQLPacket resultValue = mysqlQueryCommandPacket.getQueryData();
            currentSequenceId = resultValue.getSequenceId();
            context.write(resultValue);
            if (proxyFrontendFlushThreshold == count) {
                context.flush();
                count = 0;
            }
        }
        context.write(new MySQLEofPacket(++currentSequenceId));
    }
}
