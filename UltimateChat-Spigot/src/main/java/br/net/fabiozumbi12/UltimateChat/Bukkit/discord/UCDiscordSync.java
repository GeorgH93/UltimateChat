package br.net.fabiozumbi12.UltimateChat.Bukkit.discord;

import br.net.fabiozumbi12.UltimateChat.Bukkit.UChat;
import br.net.fabiozumbi12.UltimateChat.Bukkit.UltimateFancy;
import com.google.common.base.Charsets;
import com.google.common.io.Files;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.*;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.SimplePluginManager;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Random;

import static org.bukkit.Bukkit.getServer;

public class UCDiscordSync implements CommandExecutor, Listener, TabCompleter {
    private final HashMap<String, String> comments;
    private YamlConfiguration sync;
    private int taskId = 0;

    public UCDiscordSync(){
        this.comments = new HashMap<>();
        this.sync = new YamlConfiguration();

        File config = new File(UChat.get().getDataFolder(), "discord-sync.yml");
        if (config.exists()) {
            try {
                this.sync.load(config);
            } catch (IOException | InvalidConfigurationException e) {
                e.printStackTrace();
            }
        }

        setDefault("enable-sync", false, "Enable Discord Sync?\n" +
                "You need to setup the BOT TOKEN on config.yml and enable Discord first");

        setDefault("guild-id", "", "The guild ID");
        setDefault("sync-database.pending-codes", null, "Pending connection codes will be here!");
        setDefault("sync-database.sync-players", null, "Connected players will be here!");
        setDefault("discord-cmd-connect", ";;connect", "This is the command the player need to use on BOT Private message with code");
        setDefault("sync-database", null, "All stored players and pendent codes! Try to do not edit this manually!");
        setDefault("update-interval", 5, "Interval in minutes to send role updates to Discord");
        setDefault("name.to-discord", false, "Change the discord nickname to IN-GAME name?");
        setDefault("name.use-display-name", true, "Should send the nickname instead real player name?");

        setDefault("group-ids", null,
                "To get a role ID, mention the role with a \\ before it in a Discord channel (e.g. \\@rolename)\n" +
                        "The role need to be MENTIONABLE to allow you to get the id");
        setDefault("group-ids.group-example", "1234567890123",null);


        if (this.sync.getBoolean("enable-sync")){
            if (getServer().getPluginCommand("discord-sync").isRegistered()){
                try {
                    Field field = SimplePluginManager.class.getDeclaredField("commandMap");
                    field.setAccessible(true);
                    getServer().getPluginCommand("discord-sync").unregister((CommandMap) (field.get(getServer().getPluginManager())));
                } catch (NoSuchFieldException | IllegalAccessException e) {
                    e.printStackTrace();
                }
            }

            getServer().getPluginCommand("discord-sync").setExecutor(this);

            final int interval = this.sync.getInt("update-interval");
            taskId = Bukkit.getScheduler().runTaskTimerAsynchronously(UChat.get(), ()->{
                if (this.sync.getConfigurationSection("group-ids").getValues(false).isEmpty())
                    return;

                final int[] delay = {0};
                Bukkit.getOnlinePlayers().forEach(p -> {
                    if (getPlayerDDId(p.getName()) != null){
                        String pId = getPlayerDDId(p.getName());
                        Bukkit.getScheduler().runTaskLaterAsynchronously(UChat.get(), ()->{

                            String nick = "";
                            if (this.sync.getBoolean("name.to-discord")){
                                if (this.sync.getBoolean("name.display-name"))
                                    nick = p.getDisplayName();
                                else
                                    nick = p.getName();
                            }
                            String group = UChat.get().getVaultPerms().getPrimaryGroup(p);
                            if (getDDRoleByInGameGroup(group) != null && p.hasPermission("uchat.discord-sync.role."+getDDRoleByInGameGroup(group))){
                                UChat.get().getUCJDA().setPlayerRole(pId, getDDRoleByInGameGroup(group), this.sync.getString("guild-id"), nick, getConfigRoles());
                            }

                            delay[0] += 10;
                        }, delay[0]);
                    }

                });
            }, 20, 20 * (60 * (interval < 1 ? 1 : interval))/*secs*/).getTaskId();

            UChat.get().getUCLogger().info("- Discord Sync in use!");
        }
        saveConfig();
    }

    public void unload(){
        Bukkit.getScheduler().cancelTask(this.taskId);
    }

    String getDDCommand(){
        return this.sync.getString("discord-cmd-connect");
    }

    private boolean addPendingCode(String player, String code){
        if (getPlayerDDId(player) != null){
            return false;
        }
        this.sync.set("sync-database.pending-codes." + code, player);
        saveConfig();
        return true;
    }

    @Nullable String getPlayerPending(String player){
        try{
            for (String key:this.sync.getConfigurationSection("sync-database.pending-codes").getKeys(false)){
                if (this.sync.getString("sync-database.pending-codes." + key).equals(player)) return key;
            }
        } catch (Exception ignored){}
        return null;
    }

    String getPendentCode(String code){
        return this.sync.getString("sync-database.pending-codes." + code);
    }

    void setPlayerDDId(String ddId, String nickName, String code){
        this.sync.set("sync-database.pending-codes." + code, null);
        this.sync.set("sync-database.sync-players." + nickName, ddId);
        saveConfig();
    }

    String getSyncNickName(String ddId){
        try {
            for (String key:this.sync.getConfigurationSection("sync-database.sync-players").getKeys(false)){
                if (this.sync.getString("sync-database.sync-players." + key).equals(ddId)) return key;
            }
        } catch (Exception ignored){}
        return null;
    }

    private @Nullable String getDDRoleByInGameGroup(String group){
        return this.sync.getString("group-ids." + group, null);
    }

    private List<String> getConfigRoles(){
        List<String> roles = new ArrayList<>();
        this.sync.getConfigurationSection("group-ids").getKeys(false).forEach(k -> roles.add(this.sync.getString("group-ids." + k)));
        return roles;
    }

    private @Nullable String getPlayerDDId(String player){
        return this.sync.getString("sync-database.sync-players." + player, null);
    }

    private String getSaltString() {
        String SALTCHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890";
        StringBuilder salt = new StringBuilder();
        Random rnd = new Random();
        while (salt.length() < 10) { // length of the random string.
            int index = (int) (rnd.nextFloat() * SALTCHARS.length());
            salt.append(SALTCHARS.charAt(index));
        }
        return salt.toString();

    }


    @Override
    public boolean onCommand(CommandSender commandSender, Command command, String s, String[] args) {
        if (args.length == 0){
            commandSender.sendMessage(ChatColor.AQUA + "---------------- " + UChat.get().getPDF().getFullName() + " ----------------");
            commandSender.sendMessage(ChatColor.AQUA + "Developed by " + ChatColor.GOLD + UChat.get().getPDF().getAuthors() + ".");
            commandSender.sendMessage(ChatColor.AQUA + "Discord Sync Commands [" + ChatColor.GOLD + "/" + s + " gen|unlink" + ChatColor.AQUA + "].");
            commandSender.sendMessage(ChatColor.AQUA + "---------------------------------------------------");
            return true;
        }
        //d-sync generate
        if (args[0].equalsIgnoreCase("gen") && commandSender.hasPermission("uchat.discord-sync.cmd.generate")){
            if (args.length == 1 && commandSender instanceof Player){
                Player p = (Player)commandSender;
                String code = getSaltString();
                if (getPlayerPending(p.getName()) != null){
                    code = getPlayerPending(p.getName());
                }
                if (addPendingCode(p.getName(), code)){
                    UltimateFancy fancy = new UltimateFancy(UChat.get().getLang().get("_UChat.prefix")+" "+UChat.get().getLang().get("discord.sync-done").replace("{code}", code));
                    fancy.hoverShowText(UChat.get().getLang().get("discord.sync-click"));
                    fancy.clickSuggestCmd(code);
                    fancy.send(p);
                } else {
                    UltimateFancy fancy = new UltimateFancy(UChat.get().getLang().get("_UChat.prefix")+" "+UChat.get().getLang().get("discord.sync-fail"));
                    fancy.send(p);
                }
            }
            if (args.length == 2 && commandSender.hasPermission("uchat.discord-sync.cmd.generate.others")){
                String p = args[1];
                String code = getSaltString();
                if (getPlayerPending(p) != null){
                    code = getPlayerPending(p);
                }
                if (addPendingCode(p, code)){
                    UltimateFancy fancy = new UltimateFancy(UChat.get().getLang().get("_UChat.prefix")+" "+UChat.get().getLang().get("discord.sync-done").replace("{code}", code));
                    fancy.hoverShowText(UChat.get().getLang().get("discord.sync-click"));
                    fancy.clickSuggestCmd(code);
                    fancy.send(commandSender);
                } else {
                    UltimateFancy fancy = new UltimateFancy(UChat.get().getLang().get("_UChat.prefix")+" "+UChat.get().getLang().get("discord.sync-fail"));
                    fancy.send(commandSender);
                }
            }
            return true;
        }

        //d-sync unlink
        if (args[0].equalsIgnoreCase("unlink") && commandSender.hasPermission("uchat.discord-sync.cmd.unlink")){
            if (args.length == 1 && commandSender instanceof Player){
                Player p = (Player)commandSender;
                if (getPlayerDDId(p.getName()) != null){
                    this.sync.set("sync-database.sync-players." + p.getName(), null);
                    saveConfig();
                    UChat.get().getLang().sendMessage(p, "discord.sync-unlink");
                } else {
                    UChat.get().getLang().sendMessage(p, "discord.sync-notlink");
                }
            }
            if (args.length == 2 && commandSender.hasPermission("uchat.discord-sync.cmd.unlink.others")){
                String p = args[1];
                if (getPlayerDDId(p) != null){
                    this.sync.set("sync-database.sync-players." + p, null);
                    saveConfig();
                    UChat.get().getLang().sendMessage(commandSender, "discord.sync-unlink");
                } else {
                    UChat.get().getLang().sendMessage(commandSender, "discord.sync-notlink");
                }
            }
            return true;
        }
        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender commandSender, Command command, String s, String[] args) {
        List<String> tab = new ArrayList<>();
        if (args.length == 1){
            if (commandSender.hasPermission("uchat.discord-sync.cmd.generate"))
                tab.add("gen");
            if (commandSender.hasPermission("uchat.discord-sync.cmd.unlink"))
                tab.add("unlink");
        }
        return tab;
    }


    //config manager
    private void setDefault(String key, Object def, String comment) {
        if (def != null) {
            this.sync.set(key, this.sync.get(key, def));
        }
        if (comment != null) {
            setComment(key, comment);
        }
    }

    private void setComment(String key, String comment) {
        comments.put(key, comment);
    }

    public void saveConfig() {
        StringBuilder b = new StringBuilder();
        this.sync.options().header(null);

        b.append(""
                + "# Uchat Discord Syncronization file\n"
                + "# Author: FabioZumbi12\n"
                + "# We recommend you to use NotePad++ to edit this file and avoid TAB errors!\n"
                + "# ------------------------------------------------------------------------\n"
                + "#     In this file you can configure the discord roles synchronization    \n"
                + "#              between online players and your Discord groups             \n"
                + "# ------------------------------------------------------------------------\n"
                + "\n"
                + "# IMPORTANT NOTE: The BOT need to have a role assigned with MANAGE ROLES and MANAGE NICKNAMES\n"
                + "# granted, and this role need to be UNDER other roles you want to give to your Discord members!\n"
                + "\n");

        for (String line : this.sync.getKeys(true)) {
            String[] key = line.split("\\" + this.sync.options().pathSeparator());
            StringBuilder spaces = new StringBuilder();
            for (int i = 0; i < key.length; i++) {
                if (i == 0) continue;
                spaces.append(" ");
            }
            if (comments.containsKey(line)) {
                if (spaces.length() == 0) {
                    b.append("\n# ").append(comments.get(line).replace("\n", "\n# ")).append('\n');
                } else {
                    b.append(spaces).append("# ").append(comments.get(line).replace("\n", "\n" + spaces + "# ")).append('\n');
                }
            }
            Object value = this.sync.get(line);
            if (!this.sync.isConfigurationSection(line)) {
                if (value instanceof String) {
                    b.append(spaces).append(key[key.length - 1]).append(": '").append(value).append("'\n");
                } else if (value instanceof List<?>) {
                    if (((List<?>) value).isEmpty()) {
                        b.append(spaces).append(key[key.length - 1]).append(": []\n");
                    } else {
                        b.append(spaces).append(key[key.length - 1]).append(":\n");
                        for (Object lineCfg : (List<?>) value) {
                            if (lineCfg instanceof String) {
                                b.append(spaces).append("- '").append(lineCfg).append("'\n");
                            } else {
                                b.append(spaces).append("- ").append(lineCfg).append("\n");
                            }
                        }
                    }
                } else {
                    b.append(spaces).append(key[key.length - 1]).append(": ").append(value).append("\n");
                }
            } else {
                b.append(spaces).append(key[key.length - 1]).append(":\n");
            }
        }

        try {
            Files.write(b, new File(UChat.get().getDataFolder(), "discord-sync.yml"), Charsets.UTF_8);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

}