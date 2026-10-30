package com.nevermissapps.minecraft;

import net.minecraftforge.fml.common.Mod;

import java.io.File;

/** A entrada do Forge (1.16.5 ao 1.20.1). O NeoForge não olha esta anotação: ele entra pelo {@link NeverMissNeoForge}. */
@Mod("nevermiss")
public final class NeverMissForge {
    public NeverMissForge() {
        Forge.criar("forge", "net.minecraftforge.common.MinecraftForge", "net.minecraftforge.event.", new File("config", "nevermiss"));
    }
}
