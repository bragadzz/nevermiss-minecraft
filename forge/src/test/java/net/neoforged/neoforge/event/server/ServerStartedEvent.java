package net.neoforged.neoforge.event.server;

public class ServerStartedEvent {
    private final Object servidor;

    public ServerStartedEvent(Object servidor) {
        this.servidor = servidor;
    }

    public Object getServer() {
        return servidor;
    }
}
