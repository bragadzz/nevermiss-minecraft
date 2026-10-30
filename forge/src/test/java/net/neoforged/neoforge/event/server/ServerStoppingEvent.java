package net.neoforged.neoforge.event.server;

public class ServerStoppingEvent {
    private final Object servidor;

    public ServerStoppingEvent(Object servidor) {
        this.servidor = servidor;
    }

    public Object getServer() {
        return servidor;
    }
}
