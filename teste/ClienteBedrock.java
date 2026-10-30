import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.nio.NioEventLoopGroup;
import org.cloudburstmc.netty.channel.nethernet.NetherNetChannelFactory;
import org.cloudburstmc.netty.channel.nethernet.config.NetherChannelOption;
import org.cloudburstmc.netty.channel.nethernet.signaling.ClientInfo;
import org.cloudburstmc.netty.channel.nethernet.signaling.HttpSignalingSettings;
import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetHTTPClientSignaling;
import org.cloudburstmc.netty.util.nethernet.OperatorIdentity;
import org.cloudburstmc.protocol.bedrock.BedrockClientSession;
import org.cloudburstmc.protocol.bedrock.BedrockPeer;
import org.cloudburstmc.protocol.bedrock.PacketDirection;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.data.PacketCompressionAlgorithm;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.CompressionCodec;
import org.cloudburstmc.protocol.bedrock.netty.codec.packet.BedrockPacketCodec;
import org.cloudburstmc.protocol.bedrock.netty.codec.packet.BedrockPacketCodec_v3;
import org.cloudburstmc.protocol.bedrock.netty.initializer.BedrockChannelInitializer;
import org.cloudburstmc.protocol.bedrock.data.PlayStatus;
import org.cloudburstmc.protocol.bedrock.data.ResourcePackResponse;
import org.cloudburstmc.protocol.bedrock.data.auth.PlayerAuthenticationType;
import org.cloudburstmc.protocol.bedrock.data.payload.text.MessageAndParams;
import org.cloudburstmc.protocol.bedrock.netty.initializer.BedrockClientInitializer;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacketHandler;
import org.cloudburstmc.protocol.bedrock.packet.DisconnectPacket;
import org.cloudburstmc.protocol.bedrock.packet.LoginPacket;
import org.cloudburstmc.protocol.bedrock.packet.NetworkSettingsPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayStatusPacket;
import org.cloudburstmc.protocol.bedrock.packet.RequestNetworkSettingsPacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePackClientResponsePacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePackStackPacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePacksInfoPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetLocalPlayerAsInitializedPacket;
import org.cloudburstmc.protocol.bedrock.packet.TextPacket;
import org.cloudburstmc.protocol.bedrock.util.EncryptionUtils;
import org.cloudburstmc.protocol.common.PacketSignal;
import org.jose4j.json.JsonUtil;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.consumer.JwtConsumerBuilder;
import org.powernukkitx.network.nethernet.NetherNetFrameCodec;
import tel.schich.libdatachannel.LibDataChannelArchDetect;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Um jogador Bedrock falso para o teste/e2e-nukkit.sh: entra sem conta Xbox (o servidor com xbox-auth desligado) com o
 * gamertag que vier, até com espaço, e escreve no stdout CONECTADO, cada CHAT e SAIU. Usa a biblioteca de protocolo
 * (CloudburstMC) e o NetherNet (WebRTC, o transporte do PowerNukkitX desde o 3.0.5) que o próprio PowerNukkitX traz:
 * java -cp servidor.jar teste/ClienteBedrock.java <porta> "<gamertag>".
 * Fecha quando o stdin acaba.
 */
public class ClienteBedrock {
    public static void main(String[] args) throws Exception {
        int porta = Integer.parseInt(args[0]);
        String nome = args[1];
        // Só os pacotes que o login usa são decodificados; o resto chega cru.
        BedrockCodec codec = org.powernukkitx.network.NetworkConstants.CODEC.toBuilder().retainPackets(
                RequestNetworkSettingsPacket.class, NetworkSettingsPacket.class, LoginPacket.class, PlayStatusPacket.class,
                ResourcePacksInfoPacket.class, ResourcePackStackPacket.class, ResourcePackClientResponsePacket.class,
                SetLocalPlayerAsInitializedPacket.class, TextPacket.class, DisconnectPacket.class).build();
        LibDataChannelArchDetect.initialize();
        NioEventLoopGroup grupo = new NioEventLoopGroup(1);
        BedrockClientSession[] sessao = new BedrockClientSession[1];
        int versaoRak = codec.getRaknetProtocolVersion();
        NetherNetHTTPClientSignaling sinal = new NetherNetHTTPClientSignaling(HttpSignalingSettings.DEFAULT
                .withScheme(HttpSignalingSettings.Scheme.HTTP)
                .withClientInfo(new ClientInfo(codec.getMinecraftVersion(), codec.getProtocolVersion(), 7)));
        new Bootstrap().group(grupo)
                .channelFactory(NetherNetChannelFactory.client(sinal))
                // Sem conta Xbox: a identidade do jogador é gerada aqui. O servidor de teste é aceito como está: o token dele
                // não tem prazo, que o TokenTrust.ANY exige.
                .option(NetherChannelOption.NETHER_CLIENT_IDENTITY, identidade(nome))
                .option(NetherChannelOption.NETHER_CLIENT_SERVER_TRUST, id -> new JwtConsumerBuilder().setDisableRequireSignature()
                        .setSkipSignatureVerification().setSkipDefaultAudienceValidation().build().processToClaims(id.assertion().token()))
                .handler(new BedrockClientInitializer() {
                    // O mesmo caminho do servidor (NetherNetServerInitializer), do lado do cliente.
                    @Override
                    protected void preInitChannel(Channel ch) {
                        ch.attr(PacketDirection.ATTRIBUTE).set(PacketDirection.SERVER_BOUND);
                        ch.pipeline().addLast(NetherNetFrameCodec.NAME, NetherNetFrameCodec.INSTANCE)
                                .addLast(CompressionCodec.NAME, new CompressionCodec(
                                        BedrockChannelInitializer.getCompression(PacketCompressionAlgorithm.ZLIB, versaoRak, true), false));
                    }

                    @Override
                    protected void initPacketCodec(Channel ch) {
                        ch.pipeline().addLast(BedrockPacketCodec.NAME, new BedrockPacketCodec_v3(false));
                    }

                    @Override
                    protected BedrockPeer createPeer(Channel ch) {
                        return new BedrockPeer(ch, this::createSession) {
                            @Override
                            public int getRakVersion() {
                                return versaoRak;
                            }
                        };
                    }

                    @Override
                    protected void initSession(BedrockClientSession s) {
                        sessao[0] = s;
                        s.setCodec(codec);
                        s.setPacketHandler(new Tratador(s, codec, nome));
                        RequestNetworkSettingsPacket p = new RequestNetworkSettingsPacket();
                        p.setClientNetworkVersion(codec.getProtocolVersion());
                        s.sendPacketImmediately(p);
                    }
                })
                .connect(new InetSocketAddress("127.0.0.1", porta)).syncUninterruptibly();
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        while (in.readLine() != null) {
            // nada: o jogador só fica no servidor
        }
        if (sessao[0] != null && sessao[0].isConnected()) sessao[0].disconnect("fim do teste");
        grupo.shutdownGracefully();
        System.out.println("SAIU fim do teste");
        System.exit(0);
    }

    /** A identidade que assina a oferta do WebRTC: uma chave nova, com prazo (o servidor recusa o token sem "exp"). */
    private static OperatorIdentity identidade(String nome) throws Exception {
        KeyPair k = EncryptionUtils.createKeyPair();
        return new OperatorIdentity(k.getPrivate(), k.getPublic(), Instant.now().plusSeconds(86400), nome);
    }

    static final class Tratador implements BedrockPacketHandler {
        final BedrockClientSession s;
        final BedrockCodec codec;
        final String nome;

        Tratador(BedrockClientSession s, BedrockCodec codec, String nome) {
            this.s = s;
            this.codec = codec;
            this.nome = nome;
        }

        @Override
        public PacketSignal handle(NetworkSettingsPacket p) {
            s.setCompression(p.getCompressionAlgorithm());
            try {
                s.sendPacketImmediately(login());
            } catch (Exception e) {
                sair("login: " + e);
            }
            return PacketSignal.HANDLED;
        }

        @Override
        public PacketSignal handle(ResourcePacksInfoPacket p) {
            responder(ResourcePackResponse.DOWNLOADING_FINISHED);
            return PacketSignal.HANDLED;
        }

        @Override
        public PacketSignal handle(ResourcePackStackPacket p) {
            responder(ResourcePackResponse.RESOURCE_PACK_STACK_FINISHED);
            return PacketSignal.HANDLED;
        }

        @Override
        public PacketSignal handle(PlayStatusPacket p) {
            if (p.getStatus() == PlayStatus.PLAYER_SPAWN) {
                s.sendPacketImmediately(new SetLocalPlayerAsInitializedPacket());
                System.out.println("CONECTADO");
            } else if (p.getStatus() != PlayStatus.LOGIN_SUCCESS) {
                sair(String.valueOf(p.getStatus()));
            }
            return PacketSignal.HANDLED;
        }

        @Override
        public PacketSignal handle(TextPacket p) {
            // A mensagem traduzida (o tell) chega como a chave mais os parâmetros.
            String extra = p.getBody() instanceof MessageAndParams ? " " + ((MessageAndParams) p.getBody()).getParameterList() : "";
            System.out.println("CHAT " + (p.getBody() == null ? "" : p.getBody().getMessage()) + extra);
            return PacketSignal.HANDLED;
        }

        @Override
        public PacketSignal handle(DisconnectPacket p) {
            sair(p.getReason() + " " + (p.getMessages() == null ? "" : p.getMessages().getMessage()));
            return PacketSignal.HANDLED;
        }

        @Override
        public void onDisconnect(String motivo) {
            sair(motivo);
        }

        private void responder(ResourcePackResponse r) {
            ResourcePackClientResponsePacket p = new ResourcePackClientResponsePacket();
            p.setResponse(r);
            s.sendPacketImmediately(p);
        }

        private static void sair(String motivo) {
            System.out.println("SAIU " + motivo);
            System.exit(0);
        }

        /** Sem conta Xbox: a cadeia é um JWT só, assinado pela própria chave (o servidor aceita com xbox-auth desligado). */
        private LoginPacket login() throws Exception {
            KeyPair chave = EncryptionUtils.createKeyPair();
            String publica = Base64.getEncoder().encodeToString(chave.getPublic().getEncoded());
            long agora = System.currentTimeMillis() / 1000;
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("displayName", nome);
            extra.put("identity", UUID.nameUUIDFromBytes(("pocket-auth-1-xuid:" + nome).getBytes(StandardCharsets.UTF_8)).toString());
            extra.put("XUID", "");
            extra.put("titleId", "896928775");
            Map<String, Object> cadeia = new LinkedHashMap<>();
            cadeia.put("extraData", extra);
            cadeia.put("identityPublicKey", publica);
            cadeia.put("nbf", agora - 60);
            cadeia.put("exp", agora + 86400);

            Map<String, Object> d = new LinkedHashMap<>();
            d.put("ClientRandomId", 1L);
            d.put("CompatibleWithClientSideChunkGen", false);
            d.put("CurrentInputMode", 1);
            d.put("DefaultInputMode", 1);
            d.put("DeviceId", UUID.nameUUIDFromBytes(nome.getBytes(StandardCharsets.UTF_8)).toString());
            d.put("DeviceModel", "teste");
            d.put("DeviceOS", 1);
            d.put("GameVersion", codec.getMinecraftVersion());
            d.put("GraphicsMode", 1);
            d.put("GuiScale", 0);
            d.put("ClientIsEditorCapable", false);
            d.put("LanguageCode", "pt_BR");
            d.put("MaxViewDistance", 8);
            d.put("MemoryTier", 2);
            d.put("PlatformOfflineId", "");
            d.put("PlatformOnlineId", "");
            d.put("PlatformType", 0);
            d.put("SelfSignedId", UUID.randomUUID().toString());
            d.put("ServerAddress", String.valueOf(s.getSocketAddress()));
            d.put("ThirdPartyName", nome);
            d.put("TrustedSkin", false);
            d.put("UIProfile", 0);
            d.put("ClientEditorConnectionIntent", 0);
            d.put("SkinId", "teste");
            d.put("SkinData", Base64.getEncoder().encodeToString(new byte[64 * 64 * 4]));
            d.put("SkinImageWidth", 64);
            d.put("SkinImageHeight", 64);
            d.put("SkinResourcePatch", Base64.getEncoder().encodeToString(
                    "{\"geometry\":{\"default\":\"geometry.humanoid.custom\"}}".getBytes(StandardCharsets.UTF_8)));
            d.put("SkinGeometryData", "");
            d.put("ArmSize", "wide");
            d.put("SkinColor", "#0");

            LoginPacket l = new LoginPacket();
            l.setClientNetworkVersion(codec.getProtocolVersion());
            l.setAuthenticationType(PlayerAuthenticationType.SELF_SIGNED);
            l.setChain(Collections.singletonList(assinar(chave, cadeia)));
            l.setToken("");
            l.setClientJwt(assinar(chave, d));
            return l;
        }

        private static String assinar(KeyPair chave, Map<String, Object> corpo) throws Exception {
            JsonWebSignature jws = new JsonWebSignature();
            jws.setAlgorithmHeaderValue("ES384");
            jws.setHeader("x5u", Base64.getEncoder().encodeToString(chave.getPublic().getEncoded()));
            jws.setPayload(JsonUtil.toJson(corpo));
            jws.setKey(chave.getPrivate());
            return jws.getCompactSerialization();
        }
    }
}
