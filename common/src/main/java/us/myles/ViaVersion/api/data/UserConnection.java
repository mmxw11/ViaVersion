package us.myles.ViaVersion.api.data;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandler;
import io.netty.channel.socket.SocketChannel;
import lombok.Data;
import net.md_5.bungee.api.ChatColor;
import us.myles.ViaVersion.api.Via;
import us.myles.ViaVersion.api.ViaVersionConfig;
import us.myles.ViaVersion.protocols.base.ProtocolInfo;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Data
public class UserConnection {
    private final SocketChannel channel;
    Map<Class, StoredObject> storedObjects = new ConcurrentHashMap<>();
    private boolean active = true;
    private boolean pendingDisconnect = false;
    private Object lastPacket;
    private long sentPackets = 0L;
    private long receivedPackets = 0L;
    // Used for tracking pps
    private long startTime = 0L;
    private long intervalPackets = 0L;
    private long packetsPerSecond = -1L;
    // Used for handling warnings (over time)
    private int secondsObserved = 0;
    private int warnings = 0;


    public UserConnection(SocketChannel socketChannel) {
        this.channel = socketChannel;
    }

    /**
     * Get an object from the storage
     *
     * @param objectClass The class of the object to get
     * @param <T>         The type of the class you want to get.
     * @return The requested object
     */
    public <T extends StoredObject> T get(Class<T> objectClass) {
        return (T) storedObjects.get(objectClass);
    }

    /**
     * Check if the storage has an object
     *
     * @param objectClass The object class to check
     * @return True if the object is in the storage
     */
    public boolean has(Class<? extends StoredObject> objectClass) {
        return storedObjects.containsKey(objectClass);
    }

    /**
     * Put an object into the stored objects based on class
     *
     * @param object The object to store.
     */
    public void put(StoredObject object) {
        storedObjects.put(object.getClass(), object);
    }

    /**
     * Clear all the stored objects
     * Used for bungee when switching servers.
     */
    public void clearStoredObjects() {
        storedObjects.clear();
    }

    /**
     * Send a raw packet to the player
     *
     * @param packet        The raw packet to send
     * @param currentThread Should it run in the same thread
     */
    public void sendRawPacket(final ByteBuf packet, boolean currentThread) {
        final ChannelHandler handler = channel.pipeline().get(Via.getManager().getInjector().getEncoderName());
        if (currentThread) {
            channel.pipeline().context(handler).writeAndFlush(packet);
        } else {
            channel.eventLoop().submit(new Runnable() {
                @Override
                public void run() {
                    channel.pipeline().context(handler).writeAndFlush(packet);
                }
            });
        }
    }

    /**
     * Send a raw packet to the player with returning the future
     *
     * @param packet The raw packet to send
     * @return ChannelFuture of the packet being sent
     */
    public ChannelFuture sendRawPacketFuture(final ByteBuf packet) {
        final ChannelHandler handler = channel.pipeline().get(Via.getManager().getInjector().getEncoderName());
        return channel.pipeline().context(handler).writeAndFlush(packet);
    }

    /**
     * Send a raw packet to the player (netty thread)
     *
     * @param packet The packet to send
     */
    public void sendRawPacket(final ByteBuf packet) {
        sendRawPacket(packet, false);
    }
    

    /**
     * 
     * Send a raw packet to the server
     *
     * @param packet        The raw packet to send
     * @param currentThread Should it run in the same thread
     */
    public void sendRawPacketToServer(final ByteBuf packet, boolean currentThread) {
        final ChannelHandler handler = channel.pipeline().get(Via.getManager().getInjector().getDecoderName());
        final ChannelHandlerContext context = channel.pipeline().context(handler);
        if (currentThread) {
            try {
                ((ChannelInboundHandler) handler).channelRead(context, packet);
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else {
            channel.eventLoop().submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        ((ChannelInboundHandler) handler).channelRead(context, packet);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            });
        }
    }
    
    /**
     * Send a raw packet to the server (netty thread)
     *
     * @param packet The packet to send
     */
    public void sendRawPacketToServer(final ByteBuf packet) {
        sendRawPacketToServer(packet, false);
    }
    /**
     * Used for incrementing the number of packets sent to the client
     */
    public void incrementSent() {
        this.sentPackets++;
    }

    /**
     * Used for incrementing the number of packets received from the client
     *
     * @return True if the interval has reset
     */
    public boolean incrementReceived() {
        // handle stats
        Long diff = System.currentTimeMillis() - startTime;
        if (diff >= 1000) {
            packetsPerSecond = intervalPackets;
            startTime = System.currentTimeMillis();
            intervalPackets = 1;
            return true;
        } else {
            intervalPackets++;
        }
        // increase total
        this.receivedPackets++;
        return false;
    }

    public boolean handlePPS() {
        ViaVersionConfig conf = Via.getConfig();
        // Max PPS Checker
        if (conf.getMaxPPS() > 0) {
            if (getPacketsPerSecond() >= conf.getMaxPPS()) {
                disconnect(conf.getMaxPPSKickMessage().replace("%pps", ((Long) getPacketsPerSecond()).intValue() + ""));
                return true; // don't send current packet
            }
        }

        // Tracking PPS Checker
        if (conf.getMaxWarnings() > 0 && conf.getTrackingPeriod() > 0) {
            if (getSecondsObserved() > conf.getTrackingPeriod()) {
                // Reset
                setWarnings(0);
                setSecondsObserved(1);
            } else {
                setSecondsObserved(getSecondsObserved() + 1);
                if (getPacketsPerSecond() >= conf.getWarningPPS()) {
                    setWarnings(getWarnings() + 1);
                }

                if (getWarnings() >= conf.getMaxWarnings()) {
                    disconnect(conf.getMaxWarningsKickMessage().replace("%pps", ((Long) getPacketsPerSecond()).intValue() + ""));
                    return true; // don't send current packet
                }
            }
        }
        return false;
    }

    /**
     * Disconnect a connection
     *
     * @param reason The reason to use, not used if player is not active.
     */
    public void disconnect(final String reason) {
        if (!getChannel().isOpen()) return;
        if (pendingDisconnect) return;
        pendingDisconnect = true;
        if (get(ProtocolInfo.class).getUuid() != null) {
            final UUID uuid = get(ProtocolInfo.class).getUuid();
            Via.getPlatform().runSync(new Runnable() {
                @Override
                public void run() {
                    if (!Via.getPlatform().kickPlayer(uuid, ChatColor.translateAlternateColorCodes('&', reason))) {
                        getChannel().close(); // =)
                    }
                }
            });
        }

    }
}
