/**
    Copyright (C) 2014 by jabelar

    This file is part of jabelar's Minecraft Forge modding examples; as such,
    you can redistribute it and/or modify it under the terms of the GNU
    General Public License as published by the Free Software Foundation,
    either version 3 of the License, or (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU General Public License for more details.

    For a copy of the GNU General Public License see <http://www.gnu.org/licenses/>.

    If you're interested in licensing the code under different terms you can
    contact the author at julian_abelar@hotmail.com 
*/

package com.blogspot.michaelsebero.movinglightsource.proxy;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import com.blogspot.michaelsebero.movinglightsource.EventHandler;
import com.blogspot.michaelsebero.movinglightsource.MainMod;
import com.blogspot.michaelsebero.movinglightsource.OreGenEventHandler;
import com.blogspot.michaelsebero.movinglightsource.TerrainGenEventHandler;
import com.blogspot.michaelsebero.movinglightsource.gui.GuiHandler;
import com.blogspot.michaelsebero.movinglightsource.networking.MessageExtendedReachAttack;
import com.blogspot.michaelsebero.movinglightsource.networking.MessageRequestItemStackRegistryFromClient;
import com.blogspot.michaelsebero.movinglightsource.networking.MessageSendItemStackRegistryToServer;
import com.blogspot.michaelsebero.movinglightsource.networking.MessageSyncEntityToClient;
import com.blogspot.michaelsebero.movinglightsource.networking.MessageToClient;
import com.blogspot.michaelsebero.movinglightsource.networking.MessageToServer;
import com.blogspot.michaelsebero.movinglightsource.tileentities.TileEntityMovingLightSource;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.EnumCreatureType;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.biome.Biome;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerAboutToStartEvent;
import net.minecraftforge.fml.common.event.FMLServerStartedEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppedEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppingEvent;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.common.registry.EntityRegistry;
import net.minecraftforge.fml.common.registry.GameRegistry;
import net.minecraftforge.fml.relauncher.Side;

/**
 * Common proxy for both client and server sides
 * Improved with type safety and better error handling
 */
public class CommonProxy 
{
    protected int modEntityID = 0;
    
    /**
     * Registry of all item stacks including subtypes
     */
    protected List<ItemStack> itemStackRegistry = new ArrayList<>();
     
    public void fmlLifeCycleEvent(FMLPreInitializationEvent event)
    { 
        // Load configuration before doing anything else
        initConfig(event);

        // Register mod components
        registerTileEntities();
        registerModEntities();
        registerEntitySpawns();
        registerFuelHandlers();
        registerSimpleNetworking();
    }

    public void fmlLifeCycleEvent(FMLInitializationEvent event)
    {
        // Register custom event listeners
        registerEventListeners();
         
        // Register recipes (allows use of items from other mods)
        registerRecipes();
        
        // Register advancements
        registerAdvancements();
        
        // Register GUI handlers
        registerGuiHandlers();
    }
    
    public void registerGuiHandlers() 
    {
        NetworkRegistry.INSTANCE.registerGuiHandler(MainMod.instance, new GuiHandler());     
    }

    public void fmlLifeCycleEvent(FMLPostInitializationEvent event)
    {
        // Inter-mod compatibility setup
        initItemStackRegistry();    
    }

    public void fmlLifeCycleEvent(FMLServerAboutToStartEvent event) 
    {
        // Server initialization logic here
    }

    public void fmlLifeCycleEvent(FMLServerStartedEvent event) 
    {
        // Post-server-start logic here
    }

    public void fmlLifeCycleEvent(FMLServerStoppingEvent event) 
    {
        // Server shutdown preparation
    }

    public void fmlLifeCycleEvent(FMLServerStoppedEvent event) 
    {
        // Cleanup after server stops
    }

    public void fmlLifeCycleEvent(FMLServerStartingEvent event) 
    {
        // Register server commands
        // event.registerServerCommand(new CommandStructureCapture());
    }
        
    /**
     * Registers the simple networking channel and messages for both sides
     * Thanks to diesieben07 for the tutorial
     */
    protected void registerSimpleNetworking() 
    {
        System.out.println("Registering simple networking");
        MainMod.network = NetworkRegistry.INSTANCE.newSimpleChannel(MainMod.NETWORK_CHANNEL_NAME);

        int packetId = 0;
        // Register messages from client to server
        MainMod.network.registerMessage(MessageToServer.Handler.class, MessageToServer.class, packetId++, Side.SERVER);
        // Register messages from server to client
        MainMod.network.registerMessage(MessageToClient.Handler.class, MessageToClient.class, packetId++, Side.CLIENT);
        MainMod.network.registerMessage(MessageSyncEntityToClient.Handler.class, MessageSyncEntityToClient.class, packetId++, Side.CLIENT);
        MainMod.network.registerMessage(MessageExtendedReachAttack.Handler.class, MessageExtendedReachAttack.class, packetId++, Side.SERVER);
        MainMod.network.registerMessage(MessageSendItemStackRegistryToServer.Handler.class, MessageSendItemStackRegistryToServer.class, packetId++, Side.SERVER);
        MainMod.network.registerMessage(MessageRequestItemStackRegistryFromClient.Handler.class, MessageRequestItemStackRegistryFromClient.class, packetId++, Side.CLIENT);
    }
    
    /**
     * Returns a side-appropriate EntityPlayer for use during message handling
     * Thanks to CoolAlias for this tip!
     */
    public EntityPlayer getPlayerEntityFromContext(MessageContext ctx) 
    {
        return ctx.getServerHandler().player;
    }
    
    /**
     * Process the configuration
     * @param event Pre-initialization event
     */
    protected void initConfig(FMLPreInitializationEvent event)
    {
        MainMod.configFile = event.getSuggestedConfigurationFile();
        System.out.println(MainMod.MODNAME + " config path = " + MainMod.configFile.getAbsolutePath());
        System.out.println("Config file exists = " + MainMod.configFile.canRead());
        
        MainMod.config = new Configuration(MainMod.configFile);
        syncConfig();
    }
    
    /**
     * Sync the configuration
     * Public so it can handle in-game changes
     */
    public void syncConfig()
    {
        MainMod.config.load();
        
        MainMod.allowHeldItemsToGiveOffLight = MainMod.config.get(
            Configuration.CATEGORY_GENERAL, 
            "Held items can give off light", 
            true, 
            "Holding certain items like torches and glowstone will give off light."
        ).getBoolean(true);
        System.out.println("Allow held items to give off light = " + MainMod.allowHeldItemsToGiveOffLight);
        
        MainMod.allowTorchesToBurnEntities = MainMod.config.get(
            Configuration.CATEGORY_GENERAL, 
            "Torches can burn entities", 
            true, 
            "Attacking with regular torch will set entities on fire."
        ).getBoolean(true);
        System.out.println("Allow torches to burn entities = " + MainMod.allowTorchesToBurnEntities);
        
        MainMod.allowBurningEntitiesToGiveOffLight = MainMod.config.get(
            Configuration.CATEGORY_GENERAL, 
            "Burning entities give off light", 
            true, 
            "When an entity is burning it gives off same light as a fire block."
        ).getBoolean(true);
        System.out.println("Burning entities give off light = " + MainMod.allowBurningEntitiesToGiveOffLight);
        
        MainMod.config.save();
    }

    /** 
     * Registers fluids (currently unused)
     */
    public void registerFluids()
    {
        // Example: Fluid testFluid = new Fluid("testfluid");
        // FluidRegistry.registerFluid(testFluid);
    }
    
    /**
     * Registers tile entities
     */
    public void registerTileEntities()
    {
        System.out.println("Registering tile entities");
        GameRegistry.registerTileEntity(TileEntityMovingLightSource.class, "tileEntityMovingLightSource");               
    }

    /**
     * Registers recipes (currently unused)
     */
    public void registerRecipes()
    {
        // Recipe registration would go here
    }

    /**
     * Registers entities as mod entities
     * Fixed with proper generic types to eliminate warnings
     */
    protected void registerModEntities()
    {    
        System.out.println("Registering entities");
        // Example: registerModEntityWithEgg(EntityExample.class, "example", 0xE18519, 0x000000);
    }
 
    /**
     * Registers an entity as a mod entity with no tracking
     * @param entityClass The entity class to register
     * @param entityName The registry name for the entity
     */
    protected void registerModEntity(Class<? extends Entity> entityClass, String entityName)
    {
        final ResourceLocation resourceLocation = new ResourceLocation(MainMod.MODID, entityName);
        EntityRegistry.registerModEntity(
            resourceLocation, 
            entityClass, 
            entityName, 
            ++modEntityID, 
            MainMod.instance, 
            80, 
            3, 
            false
        );
    }

    /**
     * Registers an entity as a mod entity with fast tracking
     * Good for fast moving objects like throwables
     * @param entityClass The entity class to register
     * @param entityName The registry name for the entity
     */
    protected void registerModEntityFastTracking(Class<? extends Entity> entityClass, String entityName)
    {
        final ResourceLocation resourceLocation = new ResourceLocation(MainMod.MODID, entityName);
        EntityRegistry.registerModEntity(
            resourceLocation, 
            entityClass, 
            entityName, 
            ++modEntityID, 
            MainMod.instance, 
            80, 
            10, 
            true
        );
    }

    /**
     * Registers a mod entity with a spawn egg
     * @param entityClass The entity class to register
     * @param entityName The registry name for the entity
     * @param eggColor Primary egg color
     * @param eggSpotsColor Secondary egg color (spots)
     */
    public void registerModEntityWithEgg(
        Class<? extends Entity> entityClass, 
        String entityName, 
        int eggColor, 
        int eggSpotsColor)
    {
        final ResourceLocation resourceLocation = new ResourceLocation(MainMod.MODID, entityName);
        EntityRegistry.registerModEntity(
            resourceLocation, 
            entityClass, 
            entityName, 
            ++modEntityID, 
            MainMod.instance, 
            80, 
            3, 
            false, 
            eggColor, 
            eggSpotsColor
        );
    }

    /**
     * Registers entity natural spawns
     */
    protected void registerEntitySpawns()
    {
        System.out.println("Registering natural spawns");
        // Example:
        // EntityRegistry.addSpawn(EntityExample.class, 6, 1, 5, EnumCreatureType.CREATURE, Biome.PLAINS);
    }
 
    /**
     * Add spawns for all biomes
     * @param entity The entity to spawn
     * @param chance Spawn probability
     * @param minGroup Minimum group size
     * @param maxGroup Maximum group size
     */
    protected void addSpawnAllBiomes(EntityLiving entity, int chance, int minGroup, int maxGroup)
    {
        Iterator<ResourceLocation> allBiomesIterator = Biome.REGISTRY.getKeys().iterator();
        while (allBiomesIterator.hasNext())
        {
            Biome nextBiome = Biome.REGISTRY.getObject(allBiomesIterator.next());
            EntityRegistry.addSpawn(
                entity.getClass(), 
                chance, 
                minGroup, 
                maxGroup, 
                EnumCreatureType.CREATURE, 
                nextBiome
            );
        }
    }
     
    /**
     * Register fuel handlers
     */
    protected void registerFuelHandlers()
    {
        System.out.println("Registering fuel handlers");
        // Example: GameRegistry.registerFuelHandler(handler);
    }
 
    /**
     * Register event listeners
     */
    protected void registerEventListeners() 
    {
        System.out.println("Registering event listeners");
        MinecraftForge.EVENT_BUS.register(new EventHandler());
        MinecraftForge.TERRAIN_GEN_BUS.register(new TerrainGenEventHandler());
        MinecraftForge.ORE_GEN_BUS.register(new OreGenEventHandler());        
    }
    
    /**
     * Register Advancements (1.12+ feature)
     */
    protected void registerAdvancements()
    {
        // Advancement registration would go here
    }
    
    /**
     * Initialize ItemStack registry (override in ClientProxy)
     */
    protected void initItemStackRegistry()
    {
        // Default implementation does nothing
    }

    /**
     * Set the ItemStack registry
     * @param registry The list of ItemStacks to set
     */
    public void setItemStackRegistry(List<ItemStack> registry)
    {
        itemStackRegistry = registry;
    }
    
    /**
     * Get the ItemStack registry
     * @return The list of registered ItemStacks
     */
    public List<ItemStack> getItemStackRegistry()
    {
        return itemStackRegistry;
    }
        
    /**
     * Converts ItemStack list to network packet payload
     * Works directly on ByteBuf for efficiency
     * @param buffer The buffer to write to
     */
    public void convertItemStackListToPayload(ByteBuf buffer)
    {
        Iterator<ItemStack> iterator = itemStackRegistry.iterator();
       
        while (iterator.hasNext())
        {          
            ItemStack stack = iterator.next();
            
            // Write item ID and metadata
            buffer.writeInt(Item.getIdFromItem(stack.getItem()));
            buffer.writeInt(stack.getMetadata());
            
            // Handle NBT data
            boolean hasNBT = stack.hasTagCompound();
            buffer.writeBoolean(hasNBT);
            if (hasNBT)
            {
                NBTTagCompound nbt = stack.getTagCompound();
                if (nbt != null)
                {
                    ByteBufUtils.writeTag(buffer, nbt);
                }
            }
            
            iterator.remove(); // Prevent ConcurrentModificationException
        }
    }

    /**
     * Converts network packet payload to ItemStack list
     * @param buffer The buffer to read from
     * @return List of ItemStacks decoded from the buffer
     */
    public List<ItemStack> convertPayloadToItemStackList(ByteBuf buffer)
    {
        List<ItemStack> list = new ArrayList<>();
        
        while (buffer.isReadable())
        {
            int itemId = buffer.readInt();
            int metadata = buffer.readInt();
            ItemStack stack = new ItemStack(Item.getItemById(itemId), 1, metadata);
            
            // Handle NBT data
            boolean hasNBT = buffer.readBoolean();
            if (hasNBT)
            {
                NBTTagCompound nbt = ByteBufUtils.readTag(buffer);
                if (nbt != null)
                {
                    stack.setTagCompound(nbt);
                }
            }
            
            list.add(stack);
        }

        return list;      
    }
}
