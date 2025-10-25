/**
    Copyright (C) 2015 by jabelar

    This file is part of jabelar's Minecraft Forge modding examples; as such,
    you can redistribute it and/or modify it under the terms of the GNU
    General Public License as published by the Free Software Foundation,
    either version 3 of the License, or (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU General Public License for more details.

    For a copy of the GNU General Public License see <http://www.gnu.org/licenses/>.
*/

package com.blogspot.michaelsebero.movinglightsource.blocks;

import java.util.HashMap;
import java.util.Iterator;

import com.blogspot.michaelsebero.movinglightsource.registries.BlockRegistry;
import com.blogspot.michaelsebero.movinglightsource.tileentities.TileEntityMovingLightSource;
import com.blogspot.michaelsebero.movinglightsource.utilities.Utilities;

import net.minecraft.block.Block;
import net.minecraft.block.ITileEntityProvider;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * @author jabelar
 * Improved version with better performance and bug fixes
 */
public class BlockMovingLightSource extends Block implements ITileEntityProvider
{
    // Use a more efficient data structure and cache the block reference
    // Public so EntityItem can check if dropped items should emit light
    public static final HashMap<Item, Block> LIGHT_SOURCE_MAP = new HashMap<>();
    private static final AxisAlignedBB BOUNDING_BOX = new AxisAlignedBB(0.5D, 0.5D, 0.5D, 0.5D, 0.5D, 0.5D);
    
    // Cache for performance - avoid repeated map lookups
    private static Block cachedMainHandBlock = null;
    private static Block cachedOffHandBlock = null;
    private static ItemStack cachedMainHandStack = ItemStack.EMPTY;
    private static ItemStack cachedOffHandStack = ItemStack.EMPTY;

    public BlockMovingLightSource(String parName)
    {
        super(Material.AIR);
        Utilities.setBlockName(this, parName);
        setDefaultState(blockState.getBaseState());
        setTickRandomly(false);
        setLightLevel(1.0F);
    }
    
    // Initialize light source mappings - call only after all items/blocks registered
    public static void initMapLightSources()
    {
        LIGHT_SOURCE_MAP.clear(); // Ensure clean state
        
        // Add all light-emitting items with their corresponding light blocks
        addLightSource(Item.getItemFromBlock(Blocks.BEACON), BlockRegistry.MOVING_LIGHT_SOURCE_15);
        addLightSource(Item.getItemFromBlock(Blocks.LIT_PUMPKIN), BlockRegistry.MOVING_LIGHT_SOURCE_15);
        addLightSource(Items.LAVA_BUCKET, BlockRegistry.MOVING_LIGHT_SOURCE_15);
        addLightSource(Item.getItemFromBlock(Blocks.GLOWSTONE), BlockRegistry.MOVING_LIGHT_SOURCE_15);
        addLightSource(Items.GLOWSTONE_DUST, BlockRegistry.MOVING_LIGHT_SOURCE_15);
        addLightSource(Item.getItemFromBlock(Blocks.SEA_LANTERN), BlockRegistry.MOVING_LIGHT_SOURCE_15);
        addLightSource(Item.getItemFromBlock(Blocks.END_ROD), BlockRegistry.MOVING_LIGHT_SOURCE_14);
        addLightSource(Item.getItemFromBlock(Blocks.TORCH), BlockRegistry.MOVING_LIGHT_SOURCE_14);
        addLightSource(Item.getItemFromBlock(Blocks.REDSTONE_TORCH), BlockRegistry.MOVING_LIGHT_SOURCE_9);
        addLightSource(Item.getItemFromBlock(Blocks.REDSTONE_ORE), BlockRegistry.MOVING_LIGHT_SOURCE_7);
        
        // Remove any AIR items that may have been added
        LIGHT_SOURCE_MAP.remove(Items.AIR);
        
        System.out.println("Registered " + LIGHT_SOURCE_MAP.size() + " light-emitting items");
    }
    
    // Helper method to safely add light sources
    private static void addLightSource(Item item, Block block)
    {
        if (item != null && item != Items.AIR && block != null)
        {
            LIGHT_SOURCE_MAP.put(item, block);
        }
    }

    public BlockMovingLightSource(String parName, float parLightLevel)
    {
        this(parName);
        setLightLevel(parLightLevel);
    }
    
    /**
     * Check if entity is holding a light-emitting item in either hand
     * Optimized to avoid null pointer exceptions
     */
    public static boolean isHoldingLightItem(EntityLivingBase entity)
    {
        if (entity == null) return false;
        
        ItemStack mainHand = entity.getHeldItemMainhand();
        ItemStack offHand = entity.getHeldItemOffhand();
        
        return (!mainHand.isEmpty() && LIGHT_SOURCE_MAP.containsKey(mainHand.getItem())) ||
               (!offHand.isEmpty() && LIGHT_SOURCE_MAP.containsKey(offHand.getItem()));
    }
    
    /**
     * Determine which light block to place based on held items
     * Returns the block with higher light level if both hands have light sources
     * Optimized with caching to reduce map lookups
     */
    public static Block lightBlockToPlace(EntityLivingBase entity)
    {
        if (entity == null)
        {
            return Blocks.AIR;
        }
        
        ItemStack mainHand = entity.getHeldItemMainhand();
        ItemStack offHand = entity.getHeldItemOffhand();
        
        // Check cache first to avoid repeated map lookups
        Block mainHandBlock = getCachedOrLookup(mainHand, true);
        Block offHandBlock = getCachedOrLookup(offHand, false);
        
        // Both hands have light sources - choose brighter one
        if (mainHandBlock != null && offHandBlock != null)
        {
            float mainLight = mainHandBlock.getLightValue(mainHandBlock.getDefaultState());
            float offLight = offHandBlock.getLightValue(offHandBlock.getDefaultState());
            return mainLight >= offLight ? mainHandBlock : offHandBlock;
        }
        
        // Only one hand has light source
        if (mainHandBlock != null) return mainHandBlock;
        if (offHandBlock != null) return offHandBlock;
        
        // No light sources
        return Blocks.AIR;
    }
    
    /**
     * Cache-aware lookup to reduce HashMap queries
     */
    private static Block getCachedOrLookup(ItemStack stack, boolean isMainHand)
    {
        if (stack.isEmpty()) return null;
        
        ItemStack cached = isMainHand ? cachedMainHandStack : cachedOffHandStack;
        
        // Check if cache is still valid
        if (ItemStack.areItemsEqual(stack, cached))
        {
            return isMainHand ? cachedMainHandBlock : cachedOffHandBlock;
        }
        
        // Cache miss - perform lookup
        Block block = LIGHT_SOURCE_MAP.get(stack.getItem());
        
        // Update cache
        if (isMainHand)
        {
            cachedMainHandStack = stack.copy();
            cachedMainHandBlock = block;
        }
        else
        {
            cachedOffHandStack = stack.copy();
            cachedOffHandBlock = block;
        }
        
        return block;
    }
    
    /**
     * Clear the cache - call when entity changes or world unloads
     */
    public static void clearCache()
    {
        cachedMainHandBlock = null;
        cachedOffHandBlock = null;
        cachedMainHandStack = ItemStack.EMPTY;
        cachedOffHandStack = ItemStack.EMPTY;
    }

    @Override
    public AxisAlignedBB getBoundingBox(IBlockState state, IBlockAccess worldIn, BlockPos pos)
    {
        return BOUNDING_BOX;
    }
    
    @Override
    public AxisAlignedBB getCollisionBoundingBox(IBlockState blockState, IBlockAccess worldIn, BlockPos pos)
    {
        return NULL_AABB;
    }

    @Override
    public boolean canCollideCheck(IBlockState state, boolean hitIfLiquid)
    {
        return false;
    }

    @Override
    public boolean isOpaqueCube(IBlockState state)
    {
        return false;
    }

    @Override
    public boolean isFullCube(IBlockState state)
    {
        return false;
    }

    @Override
    public boolean canPlaceBlockAt(World worldIn, BlockPos pos)
    {
        return true;
    }

    @Override
    public void onBlockAdded(World worldIn, BlockPos pos, IBlockState state)
    {
        // Intentionally empty - no special logic needed
    }

    @Override
    public void neighborChanged(IBlockState state, World worldIn, BlockPos pos, Block blockIn, BlockPos fromPos)
    {
        // Intentionally empty - light blocks should not react to neighbors
    }

    @Override
    public IBlockState getStateFromMeta(int meta)
    {
        return getDefaultState();
    }

    @Override
    public int getMetaFromState(IBlockState state)
    {
        return 0;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public BlockRenderLayer getBlockLayer()
    {
        return BlockRenderLayer.CUTOUT;
    }

    @Override
    public void onFallenUpon(World worldIn, BlockPos pos, Entity entityIn, float fallDistance)
    {
        // Entities should fall through this block
    }

    @Override
    public void onLanded(World worldIn, Entity entityIn)
    {
        // No landing effects
    }

    @Override
    public TileEntity createNewTileEntity(World worldIn, int meta)
    {
        return new TileEntityMovingLightSource();
    }
    
    @Override
    public boolean hasTileEntity(IBlockState state)
    {
        return true;
    }
    
    // Performance optimization - don't render sides against other blocks
    @Override
    @SideOnly(Side.CLIENT)
    public boolean shouldSideBeRendered(IBlockState blockState, IBlockAccess blockAccess, BlockPos pos, EnumFacing side)
    {
        return false;
    }
}
