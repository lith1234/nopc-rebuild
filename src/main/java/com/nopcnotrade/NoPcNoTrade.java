package com.nopcnotrade;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.InteractionResult;

public class NoPcNoTrade implements ModInitializer {
    public static final String MODID = "nopcnotrade";
    public static Item TOASTER;

    @Override
    public void onInitialize() {
        Cfg.load();
        Voices.register();
        Identifier id = Identifier.fromNamespaceAndPath(MODID, "toaster");
        TOASTER = Registry.register(BuiltInRegistries.ITEM, id,
                new Item(new Item.Properties().stacksTo(16).setId(ResourceKey.create(Registries.ITEM, id))));
        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.INGREDIENTS).register(e -> e.accept(TOASTER));
        ServerTickEvents.END_SERVER_TICK.register(Crowd::tick);
        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> Crowd.interact(player, world, hand, entity));
    }
}
