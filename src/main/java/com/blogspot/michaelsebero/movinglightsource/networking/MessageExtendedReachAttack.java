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
*/

package com.blogspot.michaelsebero.movinglightsource.networking;

import com.blogspot.michaelsebero.movinglightsource.MainMod;
import com.blogspot.michaelsebero.movinglightsource.items.IExtendedReach;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * @author jabelar
 * Fixed for Minecraft 1.12.2 compatibility
 */
public class MessageExtendedReachAttack implements IMessage 
{
    private int entityId;

    /**
     * Default constructor required for packet handling
     */
    public MessageExtendedReachAttack() 
    { 
        // Required empty constructor
    }

    /**
     * Constructor with entity ID parameter
     * @param parEntityId The ID of the entity being attacked
     */
    public MessageExtendedReachAttack(int parEntityId) 
    {
        entityId = parEntityId;
    }

    @Override
    public void fromBytes(ByteBuf buf) 
    {
        entityId = ByteBufUtils.readVarInt(buf, 4);
    }

    @Override
    public void toBytes(ByteBuf buf) 
    {
        ByteBufUtils.writeVarInt(buf, entityId, 4);
    }

    public static class Handler implements IMessageHandler<MessageExtendedReachAttack, IMessage> 
    {
        @Override
        public IMessage onMessage(final MessageExtendedReachAttack message, MessageContext ctx) 
        {
            // Get the player from context
            final EntityPlayerMP thePlayer = (EntityPlayerMP) MainMod.proxy.getPlayerEntityFromContext(ctx);
            
            if (thePlayer == null)
            {
                System.err.println("MessageExtendedReachAttack: Player is null!");
                return null;
            }
            
            // Schedule the task to run on the main server thread (thread-safe)
            thePlayer.getServer().addScheduledTask(new Runnable()
            {
                @Override
                public void run() 
                {
                    // Get the entity being attacked
                    Entity targetEntity = thePlayer.world.getEntityByID(message.entityId);
                    
                    if (targetEntity == null)
                    {
                        // Entity doesn't exist or is no longer valid
                        return;
                    }
                    
                    // Get the held item
                    ItemStack heldItem = thePlayer.getHeldItemMainhand();
                    
                    // Validate that player is holding an extended reach weapon
                    if (heldItem.isEmpty() || !(heldItem.getItem() instanceof IExtendedReach))
                    {
                        // Not holding an extended reach weapon - prevent attack
                        return;
                    }
                    
                    // Get the extended reach weapon
                    IExtendedReach extendedReachWeapon = (IExtendedReach) heldItem.getItem();
                    
                    // Calculate distance squared between player and target
                    // Fixed: Use getDistanceSq() instead of deprecated getDistanceSqToEntity()
                    double distanceSq = thePlayer.getDistanceSq(
                        targetEntity.posX, 
                        targetEntity.posY, 
                        targetEntity.posZ
                    );
                    
                    // Calculate reach squared
                    float reach = extendedReachWeapon.getReach();
                    double reachSq = reach * reach;
                    
                    // Verify the entity is within reach to prevent cheating
                    if (distanceSq <= reachSq)
                    {
                        // Attack is valid - proceed with attack
                        thePlayer.attackTargetEntityWithCurrentItem(targetEntity);
                    }
                    else
                    {
                        // Entity is out of reach - potential cheating attempt
                        System.out.println("Extended reach attack rejected: Entity out of range. Distance: " 
                            + Math.sqrt(distanceSq) + ", Max reach: " + reach);
                    }
                }
            });
            
            return null; // No response message needed
        }
    }
}
