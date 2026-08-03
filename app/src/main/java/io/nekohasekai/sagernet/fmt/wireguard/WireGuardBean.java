package io.nekohasekai.sagernet.fmt.wireguard;

import androidx.annotation.NonNull;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

import io.nekohasekai.sagernet.fmt.AbstractBean;
import io.nekohasekai.sagernet.fmt.KryoConverters;

public class WireGuardBean extends AbstractBean {

    public static class Peer {
        public String address = "";
        public Integer port = 0;
        public String publicKey = "";
        public String preSharedKey = "";
        public String allowedIPs = "0.0.0.0/0\n::/0";
        public Integer persistentKeepaliveInterval = 0;
        public String reserved = "";

        void serialize(ByteBufferOutput output) {
            output.writeString(address);
            output.writeInt(port);
            output.writeString(publicKey);
            output.writeString(preSharedKey);
            output.writeString(allowedIPs);
            output.writeInt(persistentKeepaliveInterval);
            output.writeString(reserved);
        }

        static Peer deserialize(ByteBufferInput input) {
            Peer peer = new Peer();
            peer.address = input.readString();
            peer.port = input.readInt();
            peer.publicKey = input.readString();
            peer.preSharedKey = input.readString();
            peer.allowedIPs = input.readString();
            peer.persistentKeepaliveInterval = input.readInt();
            peer.reserved = input.readString();
            return peer;
        }
    }

    public String localAddress;
    public String privateKey;
    public String peerPublicKey;
    public String peerPreSharedKey;
    public String peerAllowedIPs;
    public Integer peerPersistentKeepaliveInterval;
    public Integer mtu;
    public String reserved;
    public Integer listenPort;
    public String dnsServer;
    public List<Peer> peers;

    @Override
    public void initializeDefaultValues() {
        super.initializeDefaultValues();
        if (localAddress == null) localAddress = "";
        if (privateKey == null) privateKey = "";
        if (peerPublicKey == null) peerPublicKey = "";
        if (peerPreSharedKey == null) peerPreSharedKey = "";
        if (peerAllowedIPs == null) peerAllowedIPs = "0.0.0.0/0\n::/0";
        if (peerPersistentKeepaliveInterval == null) peerPersistentKeepaliveInterval = 0;
        if (mtu == null) mtu = 1420;
        if (reserved == null) reserved = "";
        if (listenPort == null) listenPort = 0;
        if (dnsServer == null) dnsServer = "";
        if (peers == null) peers = new ArrayList<>();
        if (peers.isEmpty()) peers.add(primaryPeer());
    }

    public Peer primaryPeer() {
        Peer peer = new Peer();
        peer.address = serverAddress;
        peer.port = serverPort;
        peer.publicKey = peerPublicKey;
        peer.preSharedKey = peerPreSharedKey;
        peer.allowedIPs = peerAllowedIPs;
        peer.persistentKeepaliveInterval = peerPersistentKeepaliveInterval;
        peer.reserved = reserved;
        return peer;
    }

    public void syncPrimaryPeer() {
        if (peers == null) peers = new ArrayList<>();
        Peer primary = primaryPeer();
        if (peers.isEmpty()) peers.add(primary); else peers.set(0, primary);
    }

    public void usePrimaryPeer(Peer peer) {
        serverAddress = peer.address;
        serverPort = peer.port;
        peerPublicKey = peer.publicKey;
        peerPreSharedKey = peer.preSharedKey;
        peerAllowedIPs = peer.allowedIPs;
        peerPersistentKeepaliveInterval = peer.persistentKeepaliveInterval;
        reserved = peer.reserved;
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        syncPrimaryPeer();
        output.writeInt(4);
        super.serialize(output);
        output.writeString(localAddress);
        output.writeString(privateKey);
        output.writeString(peerPublicKey);
        output.writeString(peerPreSharedKey);
        output.writeInt(mtu);
        output.writeString(reserved);
        output.writeString(peerAllowedIPs);
        output.writeInt(peerPersistentKeepaliveInterval);
        output.writeInt(listenPort);
        output.writeBoolean(false); // Retain the removed system slot for Kryo v3 compatibility.
        output.writeBoolean(false); // Retain the removed GSO slot for Kryo v3 compatibility.
        output.writeString(""); // Retain the removed interface name slot for Kryo v3 compatibility.
        output.writeString(dnsServer);
        output.writeInt(peers.size());
        for (Peer peer : peers) peer.serialize(output);
    }

    @Override
    public void deserialize(ByteBufferInput input) {
        int version = input.readInt();
        super.deserialize(input);
        localAddress = input.readString();
        privateKey = input.readString();
        peerPublicKey = input.readString();
        peerPreSharedKey = input.readString();
        mtu = input.readInt();
        reserved = input.readString();
        if (version >= 3) {
            peerAllowedIPs = input.readString();
            peerPersistentKeepaliveInterval = input.readInt();
            listenPort = input.readInt();
            input.readBoolean();
            input.readBoolean();
            input.readString();
            String resolverOrServer = input.readString();
            dnsServer = version >= 4 ? resolverOrServer : "";
            int peerCount = input.readInt();
            peers = new ArrayList<>(peerCount);
            for (int i = 0; i < peerCount; i++) peers.add(Peer.deserialize(input));
        } else {
            peerAllowedIPs = "0.0.0.0/0\n::/0";
            peerPersistentKeepaliveInterval = 0;
            listenPort = 0;
            dnsServer = "";
            peers = new ArrayList<>();
            peers.add(primaryPeer());
        }
    }

    @Override
    public boolean canTCPing() {
        return false;
    }

    @NotNull
    @Override
    public WireGuardBean clone() {
        return KryoConverters.deserialize(new WireGuardBean(), KryoConverters.serialize(this));
    }

    public static final Creator<WireGuardBean> CREATOR = new CREATOR<WireGuardBean>() {
        @NonNull
        @Override
        public WireGuardBean newInstance() {
            return new WireGuardBean();
        }

        @Override
        public WireGuardBean[] newArray(int size) {
            return new WireGuardBean[size];
        }
    };
}
