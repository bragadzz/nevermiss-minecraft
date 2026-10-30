package net.neoforged.neoforge.event;

import com.mojang.brigadier.CommandDispatcher;

public class RegisterCommandsEvent {
    private final CommandDispatcher<Object> dispatcher;

    public RegisterCommandsEvent(CommandDispatcher<Object> dispatcher) {
        this.dispatcher = dispatcher;
    }

    public CommandDispatcher<Object> getDispatcher() {
        return dispatcher;
    }
}
