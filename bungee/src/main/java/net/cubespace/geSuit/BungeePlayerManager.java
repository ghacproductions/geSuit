package net.cubespace.geSuit;

import java.net.InetAddress;
import java.util.Map;

import com.google.common.collect.Maps;

import net.cubespace.geSuit.core.Global;
import net.cubespace.geSuit.core.GlobalPlayer;
import net.cubespace.geSuit.core.PlayerManager;
import net.cubespace.geSuit.core.channel.ChannelManager;
import net.cubespace.geSuit.core.events.player.GlobalPlayerNicknameEvent;
import net.cubespace.geSuit.core.messages.NetworkInfoMessage;
import net.cubespace.geSuit.core.objects.BanInfo;
import net.cubespace.geSuit.events.NewPlayerJoinEvent;
import net.cubespace.geSuit.managers.ConfigManager;
import net.cubespace.geSuit.managers.LoggingManager;
import net.cubespace.geSuit.moderation.BanManager;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.event.LoginEvent;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.event.PostLoginEvent;
import net.md_5.bungee.api.event.ServerConnectedEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;

public class BungeePlayerManager extends PlayerManager implements Listener {
    private BanManager bans;
    
    public BungeePlayerManager(ChannelManager manager) {
        super(true, manager);
        
        broadcastFullUpdate();
        broadcastNetworkInfo();
    }
    
    public void initialize(BanManager bans) {
        this.bans = bans;
    }
    
    private void broadcastNetworkInfo() {
        // Build server map
        Map<Integer, String> servers = Maps.newHashMap();
        servers.put(ChannelManager.PROXY, "proxy");
        
        for (ServerInfo info : ProxyServer.getInstance().getServers().values()) {
            servers.put(info.getAddress().getPort(), info.getName());
        }
        
        // Send out the update to everyone
        for (ServerInfo info : ProxyServer.getInstance().getServers().values()) {
            int id = info.getAddress().getPort();
            channel.send(new NetworkInfoMessage(id, servers), id);
        }
        
        // Update my info
        onNetworkInfo(new NetworkInfoMessage(ChannelManager.PROXY, servers));
    }
    
    @Override
    protected void onUpdateRequestMessage() {
        super.onUpdateRequestMessage();
        broadcastNetworkInfo();
    }
    
    @EventHandler
    public void onLogin(final LoginEvent event) {
        event.registerIntent(geSuit.getPlugin());
        ProxyServer.getInstance().getScheduler().runAsync(geSuit.getPlugin(), new Runnable() {
            @Override
            public void run() {
                handleLogin(event);
                event.completeIntent(geSuit.getPlugin());
            }
        });
    }
    
    private void handleLogin(final LoginEvent event) {
        GlobalPlayer player = loadPlayer(event.getConnection().getUniqueId(), event.getConnection().getName(), event.getConnection().getAddress().getAddress());
        
        // Check player ban state
        if (player.isBanned()) {
            if (player.getBanInfo().isTemporary()) {
                // Has ban expired?
                if (System.currentTimeMillis() >= player.getBanInfo().getUntil()) {
                    player.removeBan();
                } else {
                    event.setCancelReason(bans.getBanKickReason(player.getBanInfo()));
                    geSuit.getLogger().info(ChatColor.RED + player.getName() + "'s connection refused due to being temp banned!");
                    event.setCancelled(true);
                    return;
                }
            } else {
                event.setCancelReason(bans.getBanKickReason(player.getBanInfo()));
                geSuit.getLogger().info(ChatColor.RED + player.getName() + "'s connection refused due to being banned!");
                event.setCancelled(true);
                return;
            }
        }
        
        // Check IP ban state
        BanInfo<InetAddress> ipBan = bans.getBan(player.getAddress());
        if (ipBan != null) {
            if (ipBan.isTemporary()) {
                // Has ban expired?
                if (System.currentTimeMillis() >= ipBan.getUntil()) {
                    bans.setBan(player.getAddress(), null);
                } else {
                    event.setCancelReason(bans.getBanKickReason(ipBan));
                    geSuit.getLogger().info(ChatColor.RED + player.getName() + "'s connection refused due to being temp ip-banned!");
                    event.setCancelled(true);
                    return;
                }
            } else {
                event.setCancelReason(bans.getBanKickReason(ipBan));
                geSuit.getLogger().info(ChatColor.RED + player.getName() + "'s connection refused due to being ip-banned!");
                event.setCancelled(true);
                return;
            }
        }
        
        if (!player.hasPlayedBefore()) {
            player.setNewPlayer(true);
        }
        
        onPlayerLoginInitComplete(player);
    }
    
    @EventHandler
    public void onLoginComplete(PostLoginEvent event) {
        GlobalPlayer player = getPreloadedPlayer(event.getPlayer().getUniqueId());
        if (player != null && player.hasNickname()) {
            event.getPlayer().setDisplayName(player.getNickname());
        }
    }
    
    @EventHandler
    public void onServerConnect(ServerConnectedEvent event) {
        // Ensure they are still online before continuing
        if (ProxyServer.getInstance().getPlayer(event.getPlayer().getUniqueId()) == null) {
            geSuit.getLogger().warning("ServerConnectedEvent was called on " + event.getPlayer().getName() + " but they're not online");
            return;
        }
        
        if (onServerConnect(event.getPlayer().getUniqueId())) {
            GlobalPlayer player = Global.getPlayer(event.getPlayer().getUniqueId());
            
            // Update the tracking data for this player
            geSuit.getPlugin().getTrackingManager().updateTracking(player);
            
            if (player.isNewPlayer()) {
                LoggingManager.log(ConfigManager.messages.PLAYER_CREATE.replace("{player}", player.getName()).replace("{uuid}", player.getUniqueId().toString()));

                if (ConfigManager.main.NewPlayerBroadcast) {
                    String welcomeMsg = ChatColor.translateAlternateColorCodes('&', ConfigManager.messages.NEW_PLAYER_BROADCAST.replace("{player}", player.getDisplayName()));
                    ProxyServer.getInstance().broadcast(TextComponent.fromLegacyText(welcomeMsg));
                    // Firing custom event
                    ProxyServer.getInstance().getPluginManager().callEvent(new NewPlayerJoinEvent(player.getName(), welcomeMsg));
                }

                // Teleport to new player spawn
                if (ConfigManager.spawn.SpawnNewPlayerAtNewspawn && geSuit.getPlugin().getSpawnManager().isSetNewPlayer()) {
                    // Somehow we need to make it not connect to this server, only others
                    geSuit.getPlugin().getTeleportManager().teleportToInConnection(event.getPlayer(), geSuit.getPlugin().getSpawnManager().getSpawnNewPlayer(), event.getServer().getInfo(), true);
                }
            }
            // TODO: do all other setup
        }
    }
    
    @EventHandler
    public void onNickname(GlobalPlayerNicknameEvent event) {
        // Update the tracking data for this player
        geSuit.getPlugin().getTrackingManager().updateTracking(event.getPlayer());
    }
    
    @EventHandler
    public void onQuit(PlayerDisconnectEvent event) {
        onPlayerLeave(event.getPlayer().getUniqueId());
    }
}