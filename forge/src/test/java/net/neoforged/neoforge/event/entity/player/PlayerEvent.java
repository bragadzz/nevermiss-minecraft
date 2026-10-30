package net.neoforged.neoforge.event.entity.player;

public class PlayerEvent {
    private final Object jogador;

    PlayerEvent(Object jogador) {
        this.jogador = jogador;
    }

    public Object getEntity() {
        return jogador;
    }

    public static class PlayerLoggedInEvent extends PlayerEvent {
        public PlayerLoggedInEvent(Object jogador) {
            super(jogador);
        }
    }

    public static class PlayerLoggedOutEvent extends PlayerEvent {
        public PlayerLoggedOutEvent(Object jogador) {
            super(jogador);
        }
    }
}
