package net.minecraft.command;

public interface ICommandManager {
    // executeCommand: quantas vezes o comando deu certo (0 = não existe ou falhou)
    int func_71556_a(ICommandSender quem, String comando);

    // getCommands: nome → comando
    java.util.Map<String, ICommand> func_71555_a();
}
