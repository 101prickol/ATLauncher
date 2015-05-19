package com.atlauncher.workers;

import com.atlauncher.App;
import com.atlauncher.Gsons;
import com.atlauncher.LogManager;
import com.atlauncher.data.APIResponse;
import com.atlauncher.data.DisableableMod;
import com.atlauncher.data.Downloadable;
import com.atlauncher.data.Instance;
import com.atlauncher.data.Language;
import com.atlauncher.data.Pack;
import com.atlauncher.data.PackVersion;
import com.atlauncher.data.Type;
import com.atlauncher.data.json.Action;
import com.atlauncher.data.json.CaseType;
import com.atlauncher.data.json.DownloadType;
import com.atlauncher.data.json.Library;
import com.atlauncher.data.json.Mod;
import com.atlauncher.data.json.ModType;
import com.atlauncher.data.json.Version;
import com.atlauncher.data.mojang.AssetIndex;
import com.atlauncher.data.mojang.AssetObject;
import com.atlauncher.data.mojang.DateTypeAdapter;
import com.atlauncher.data.mojang.EnumTypeAdapterFactory;
import com.atlauncher.data.mojang.FileTypeAdapter;
import com.atlauncher.data.mojang.MojangConstants;
import com.atlauncher.gui.dialogs.ModsChooser;
import com.atlauncher.utils.Utils;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import javax.swing.SwingWorker;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FilenameFilter;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;

public class InstanceInstaller
extends SwingWorker<Boolean, Void> {
    public static final Gson GSON = new GsonBuilder()
            .registerTypeAdapterFactory(new EnumTypeAdapterFactory())
            .registerTypeAdapter(Date.class, new DateTypeAdapter())
            .registerTypeAdapter(File.class, new FileTypeAdapter())
            .setPrettyPrinting()
            .create();

    public final File root;
    public final File mods;
    public final File coremods;
    public final File jarmods;
    public final File resourcepacks;
    public final File texturepacks;
    public final boolean server;
    public final String name;
    public final String code;
    public final Pack pack;
    public final PackVersion version;
    public final boolean reinstall;
    public final boolean showModsChooser;
    public final boolean legacy;
    public final List<String> forgeLibs;
    public final List<File> serverLibs;
    public final List<DisableableMod> installedMods;
    public final Version jsonVersion;
    public final ExecutorService executor;

    private boolean savedReis;
    private boolean savedZans;
    private boolean savedNEIConfig;
    private boolean savedOptions;
    private boolean savedServers;
    private boolean savedPortalGunSounds;

    private boolean hasForge;
    private boolean hasJarMods;
    private boolean extractedTexturePack;
    private boolean extractedResourcePack;
    private boolean corrupt;
    private String libsNeeded;
    private String extraArgs;
    private String mainClass;
    private String jarOrder;
    private int totalBytes;
    private int downloadedBytes;
    private int percent;
    private int memory;
    private int permgen;
    private Instance instance;
    private List<Mod> allMods;
    private List<Mod> selectedMods;

    public InstanceInstaller(String name, Pack pack, PackVersion version, boolean reinstall, boolean server, String code, boolean showModsChooser){
        this.name = name;
        this.pack = pack;
        this.version = version;
        this.showModsChooser = showModsChooser;
        this.code = code;
        this.reinstall = reinstall;
        this.legacy = this.version.getMinecraftVersion().isLegacy();
        this.server = server;
        this.installedMods = new LinkedList<DisableableMod>();
        this.forgeLibs = new LinkedList<String>();
        this.executor = Executors.newFixedThreadPool(App.settings.getConcurrentConnections());

        if(server){
            this.serverLibs = new ArrayList<File>();
            this.root = new File(App.settings.getServersDir(), this.pack.getSafeName() + "_" + this.version.getSafeVersion());
        } else{
            this.serverLibs = null;
            this.root = new File(App.settings.getInstancesDir(), this.name.replaceAll("[^A-Za-z0-9]", ""));
        }

        this.mods = new File(this.root, "mods");
        this.jarmods = new File(this.root, "jarmods");
        this.coremods = new File(this.root, "coremods");
        this.resourcepacks = new File(this.root, "resourcepacks");
        this.texturepacks = new File(this.root, "texturepacks");

        try{
            this.jsonVersion = Gsons.DEFAULT.fromJson(this.pack.getJSON(this.version.getVersion()), Version.class);
        } catch(Exception e){
            App.settings.logStackTrace("Couldn't parse JSON of pack", e);
            throw new RuntimeException(e);
        }
    }

    private List<Downloadable> getMods(){
        List<Downloadable> mods = new LinkedList<Downloadable>();

        for(Mod mod : this.selectedMods){
            if(mod.getDownload() == DownloadType.server){
                mods.add(
                                new Downloadable(mod.getUrl(), new File(App.settings.getDownloadsDir(), mod.getFile()),
                                                                        mod.hasMD5() ? null : mod.getMD5(), mod.getFilesize(), this, true
                                )
                );
            }
        }

        return mods;
    }

    private List<Downloadable> getLibraries(){
        List<Downloadable> libs = new LinkedList<Downloadable>();
        List<String> namesAdded = new LinkedList<String>();

        for(Library lib : this.jsonVersion.getLibraries()){
            if(lib.hasDepends()){
                boolean found = false;

                for(Mod mod : this.selectedMods){
                    if(lib.getDepends().equalsIgnoreCase(mod.getName())){
                        found = true;
                        break;
                    }
                }

                if(!found){
                    continue;
                }
            } else if(lib.hasDependsGroup()){
                boolean found = false;

                for(Mod mod : this.selectedMods){
                    if(lib.getDependsGroup().equalsIgnoreCase(mod.getGroup())){
                        found = true;
                        break;
                    }
                }

                if(!found){
                    continue;
                }
            }

            if(!lib.getUrl().startsWith("http://") &&
               !lib.getUrl().startsWith("https://")){

                lib.setDownloadType(DownloadType.server);
            }

            if(libsNeeded == null){
                this.libsNeeded = lib.getFile();
            } else{
                this.libsNeeded += "," + lib.getFile();
            }

            this.forgeLibs.add(lib.getFile());

            if(this.server){
                if(!lib.forServer()){
                    continue;
                }
                serverLibs.add(new File(new File(this.root, "libraries"), lib.getServer()));
            }

            File downloadTo = new File(App.settings.getLibrariesDir(), lib.getFile());

            if(lib.getDownloadType() == DownloadType.server){
                libs.add(
                                new Downloadable(
                                                        lib.getUrl(), downloadTo, lib.getMD5(),
                                                        lib.getFilesize(), this, true
                                )
                );
            } else if(lib.getDownloadType() == DownloadType.direct){
                libs.add(new Downloadable(lib.getUrl(), downloadTo, lib.getMD5(), this, false));
            } else{
                LogManager.error("DownloadType for library " + lib.getFile() + " is invalid with a value of " + lib.getDownloadType());
                this.cancel(true);
                return null;
            }

            if(lib.getFile().contains("-")){
                namesAdded.add(lib.getFile().substring(0, lib.getFile().lastIndexOf("-")));
            } else{
                namesAdded.add(lib.getFile());
            }
        }

        if(!this.server){
            for(com.atlauncher.data.mojang.Library lib : this.version.getMinecraftVersion().getMojangVersion().getLibraries()){
                if(lib.shouldInstall()){
                    if(namesAdded.contains(lib.getFile()
                                              .getName()
                                              .substring(0, lib.getFile().getName().lastIndexOf("-")))){

                        LogManager.debug("Not adding library " + lib.getName() + " as it's been overwritten already by the packs libraries");
                        continue;
                    }

                    if(!lib.shouldExtract()){
                        if(libsNeeded == null){
                            this.libsNeeded = lib.getFile().getName();
                        } else{
                            this.libsNeeded += "," + lib.getFile().getName();
                        }
                    }

                    System.out.println(lib.getURL() + " -> " + lib.getFile());

                    libs.add(
                             new Downloadable(lib.getURL(), lib.getFile(), null, this, false)
                    );
                }
            }
        }

        if(this.server){
            libs.add(
                            new Downloadable(
                                                    MojangConstants.DOWNLOAD_BASE.getURL("versions/" + this.version.getMinecraftVersion().getVersion() + "/minecraft_server." + this.version.getMinecraftVersion().getVersion() + ".jar"),
                                                    new File(App.settings.getJarsDir(), "minecraft_server." + this.version.getMinecraftVersion().getVersion() + ".jar"),
                                                    null, this, false
                            )

            );
        } else{
            libs.add(
                            new Downloadable(MojangConstants.DOWNLOAD_BASE.getURL("versions/" + this.version.getMinecraftVersion().getVersion() + "/" + this.version.getMinecraftVersion().getVersion() + ".jar"),
                                             new File(App.settings.getJarsDir(), this.version.getMinecraftVersion().getVersion() + ".jar"),
                                             null, this, false

                            )
            );
        }

        return libs;
    }

    private List<Downloadable> getResources(){
        List<Downloadable> downloads = new LinkedList<Downloadable>();
        File objectsFile = new File(App.settings.getResourcesDir(), "objects");
        File indexesFile = new File(App.settings.getResourcesDir(), "indexes");
        File virtualFile = new File(App.settings.getResourcesDir(), "virtual");

        String assetsVersion = this.version.getMinecraftVersion().getMojangVersion().getAssets();

        File virtualRoot = new File(virtualFile, assetsVersion);
        File indexFile = new File(indexesFile, assetsVersion + ".json");

        objectsFile.mkdirs();
        indexesFile.mkdirs();
        virtualFile.mkdirs();

        try{
            new Downloadable(MojangConstants.DOWNLOAD_BASE.getURL("indexes/" + assetsVersion + ".json"), indexFile, null, this, false)
                    .download(false);

            AssetIndex index = GSON.fromJson(new FileReader(indexFile), AssetIndex.class);

            if(index.isVirtual()){
                virtualRoot.mkdirs();
            }

            for(Map.Entry<String, AssetObject> entry : index.getObjects().entrySet()){
                AssetObject obj = entry.getValue();
                String fileName = obj.getHash().substring(0, 2) + "/" + obj.getHash();
                File file = new File(objectsFile, fileName);
                File vFile = new File(virtualRoot, entry.getKey());

                if(obj.needToDownload(file)){
                    downloads.add(
                                         new Downloadable(MojangConstants.RESOURCES_BASE.getURL(fileName), file,
                                                          obj.getHash(), (int) obj.getSize(), this, false, vFile, index.isVirtual()
                                         )
                    );
                } else{
                    if(index.isVirtual()){
                        vFile.mkdirs();
                        Utils.copyFile(file, vFile, true);
                    }
                }
            }
        } catch(Exception e){
            App.settings.logStackTrace(e);
        }

        return downloads;
    }

    public boolean wasModInstalled(String mod){
        return this.instance != null &&
               this.instance.wasModInstalled(mod);
    }

    public boolean isRecommendedInGroup(Mod mod){
        for(Mod modd : this.allMods){
            if(modd.equals(mod) || !modd.hasGroup()){
                continue;
            }

            if(modd.getGroup()
                   .equalsIgnoreCase(mod.getGroup()) &&
               modd.isRecommended()){

                return false;
            }
        }

        return true;
    }

    public List<Mod> getGroupedMods(Mod mod){
        List<Mod> grouped = new LinkedList<Mod>();

        for(Mod modd : this.allMods){
            if(!modd.hasGroup()){
                continue;
            }

            if(modd.getGroup().equalsIgnoreCase(mod.getGroup()) &&
               modd != mod){
                grouped.add(modd);
            }
        }

        return grouped;
    }

    public List<Mod> getLinkedMods(Mod mod){
        List<Mod> linked = new LinkedList<Mod>();

        for(Mod modd : this.allMods){
            if(!modd.hasLinked()){
                continue;
            }

            if(modd.getLinked().equalsIgnoreCase(mod.getName())){
                linked.add(modd);
            }
        }

        return linked;
    }

    public List<Mod> getModsDependencies(Mod mod){
        List<Mod> deps = new LinkedList<Mod>();

        for(String name : mod.getDepends()){
            for(Mod modd : this.allMods){
                if(modd.getName().equalsIgnoreCase(name)){
                    deps.add(modd);
                    break;
                }
            }
        }

        return deps;
    }

    public List<Mod> dependedMods(Mod mod){
        List<Mod> deps = new LinkedList<Mod>();

        for(Mod modd : this.allMods){
            if(!modd.hasDepends()){
                continue;
            }

            if(modd.isADependancy(mod)){
                deps.add(modd);
            }
        }

        return deps;
    }

    public boolean hasADependency(Mod mod){
        for(Mod modd : this.allMods){
            if(!modd.hasDepends()){
                continue;
            }

            if(modd.isADependancy(mod)){
                return true;
            }
        }

        return false;
    }

    public String getShareCodeData(String code){
        String data = null;

        try{
            APIResponse resp = Gsons.DEFAULT.fromJson(Utils.sendGetAPICall("pack/" + this.pack.getSafeName() + "/" + this.version.getSafeVersion() + "/share-code/" + code), APIResponse.class);
            data = resp.getDataAsString();
        } catch(Exception e){
            App.settings.logStackTrace(e);
        }

        return data;
    }

    public List<Mod> getAllMods(){
        return this.allMods;
    }

    public boolean hasRecommendedMods(){
        for(Mod mod : this.allMods){
            if(mod.isRecommended()){
                return true;
            }
        }

        return false;
    }

    public String getLibrariesNeeded(){
        return this.libsNeeded;
    }

    public String getMainClass(){
        return this.mainClass;
    }

    public String getExtraArgs(){
        return this.extraArgs;
    }

    public String getJarOrder(){
        return this.jarOrder;
    }

    public int getMemory(){
        return this.memory;
    }

    public int getPermgen(){
        return this.permgen;
    }

    public boolean shouldCorruptInstance(){
        return this.corrupt;
    }

    public Mod getModByName(String name){
        for(Mod mod : this.allMods){
            if(mod.getName().equalsIgnoreCase(name)){
                return mod;
            }
        }

        return null;
    }

    public void setResourcePackExtracted(){
        this.extractedResourcePack = true;
    }

    public void setTexturePackExtracted(){
        this.extractedTexturePack = true;
    }

    public void resetDownloadedBytes(int bytes){
        this.totalBytes = bytes;
        this.downloadedBytes = 0;
    }

    public void addTotalDownloadedBytes(int bytes){
        this.totalBytes += bytes;
        this.updateProgressBar();
    }

    public void addDownloadedBytes(int bytes){
        this.downloadedBytes += bytes;
        this.updateProgressBar();
    }

    public void addToJarOrder(String file){
        if(this.jarOrder == null){
            this.jarOrder = file;
        } else{
            this.jarOrder = file + "," + this.jarOrder;
        }
    }

    private void updateProgressBar(){
        float progress;
        if(this.totalBytes > 0){
            progress = ((float) this.downloadedBytes / (float) this.totalBytes) * 100;
        } else{
            progress = 0;
        }

        float done = (float) this.downloadedBytes / 1024 / 1024;
        float doing = (float) this.totalBytes / 1024 / 1024;

        if(done > doing){
            this.fireSubProgress(100, String.format("%.2f MB / %.2f MB", done, doing));
        } else{
            this.fireSubProgress((int) progress, String.format("%.2f MB / %.2f MB", done, doing));
        }
    }

    public void setInstance(Instance instance) {
        this.instance = instance;
    }

    private String getServerJar(){
        Mod forge = null;
        Mod mcpc = null;
        for(Mod mod : this.selectedMods){
            if(mod.getType() == ModType.forge){
                forge = mod;
                break;
            } else if(mod.getType() == ModType.mcpc) {
                mcpc = mod;
                break;
            }
        }

        if(mcpc != null){
            return mcpc.getFile();
        } else if(forge != null){
            return forge.getFile();
        } else{
            return "minecraft_server." + this.version.getMinecraftVersion().getVersion() + ".jar";
        }
    }

    private File getMinecraftJar(){
        if(this.server){
            return new File(this.root, "minecraft_server." + this.version.getMinecraftVersion().getVersion() + ".jar");
        } else{
            return new File(new File(this.root, "bin"), "minecraft.jar");
        }
    }

    private void mkdirs(){
        if(this.reinstall || this.server){
            Utils.delete(new File(this.root, "bin"));
            Utils.delete(new File(this.root, "config"));

            if(this.instance != null &&
               this.instance.getMinecraftVersion().equalsIgnoreCase(version.getMinecraftVersion().getVersion()) &&
               this.instance.hasCustomMods()){

                Utils.deleteWithFilter(new File(this.root, "mods"), this.instance.getCustomMods(Type.mods));
                if(this.version.getMinecraftVersion().usesCoreMods()){
                    Utils.deleteWithFilter(new File(this.root, "coremods"), this.instance.getCustomMods(Type.coremods));
                }

                if(this.reinstall){
                    Utils.deleteWithFilter(new File(this.root, "jarmods"), this.instance.getCustomMods(Type.jar));
                }
            } else{
                Utils.delete(new File(this.root, "mods"));
                if(this.version.getMinecraftVersion().usesCoreMods()){
                    Utils.delete(new File(this.root, "coremods"));
                }

                if(this.reinstall){
                    Utils.delete(new File(this.root, "jarmods"));
                }
            }
        }

        if(this.reinstall){
            Utils.delete(new File(new File(this.root, "texturepacks"), "TexturePack.zip"));
            Utils.delete(new File(new File(this.root, "resourcepacks"), "ResourcePack.zip"));
        } else{
            Utils.delete(new File(this.root, "libraries"));
        }

        if(this.instance != null){
            if(this.pack.hasDeleteArguments(true, this.version.getVersion())){
                List<File> fileDels = this.pack.getDeletes(true, this.version.getVersion(), this.instance);
                for(File file : fileDels){
                    if(file.exists()){
                        Utils.delete(file);
                    }
                }
            }

            if(this.pack.hasDeleteArguments(false, this.version.getVersion())){
                List<File> fileDels = this.pack.getDeletes(false, this.version.getVersion(), this.instance);
                for(File file : fileDels){
                    if(file.exists()){
                        Utils.delete(file);
                    }
                }
            }
        }

        File[] dirs;
        if(this.server) {
            dirs = new File[]{
                                     this.root, new File(this.root, "mods"),
                                     new File(this.root, "temp"), new File(this.root, "libraries")
            };
        } else{
            dirs = new File[]{
                                     this.root, new File(this.root, "mods"),
                                     new File(this.root, "disabledmods"), new File(this.root, "temp"),
                                     new File(this.root, "jarmods"), new File(this.root, "bin"),
                                     new File(this.root, "natives")
            };
        }

        for(File dir : dirs){
            dir.mkdir();
        }

        if(this.version.getMinecraftVersion().usesCoreMods()){
            new File(this.root, "coremods").mkdir();
        }
    }

    private boolean installUsingJson()
    throws Exception{
        if(this.jsonVersion == null){
            return false;
        }

        if(this.jsonVersion.hasMessages()){
            if(this.reinstall &&
               this.jsonVersion.getMessages().hasUpdateMessage() &&
               this.jsonVersion.getMessages().showUpdateMessage(this.pack) != 0){
                LogManager.error("Instance Install Cancelled After Viewing Message");
                cancel(true);
                return false;
            } else if(this.jsonVersion.getMessages().hasInstallMessage() &&
                      this.jsonVersion.getMessages().showInstallMessage(this.pack) != 0){
                LogManager.error("Instance Install Canceled After Viewing Message");
                cancel(true);
                return false;
            }
        }

        this.jsonVersion.compileColours();

        this.allMods = this.sortMods((this.server ? this.jsonVersion.getServerInstallMods() : this.jsonVersion.getClientInstallMods()));

        boolean hasOptional = false;
        for(Mod mod : this.allMods){
            if(mod.isOptional()){
                hasOptional = true;
                break;
            }
        }

        if(this.allMods.size() != 0 && hasOptional){
            ModsChooser modsChooser = new ModsChooser(this);

            if(this.code != null){
                modsChooser.applyShareCode(this.code);
            }

            if(this.showModsChooser){
                modsChooser.setVisible(true);
            }

            if(modsChooser.wasClosed()){
                this.cancel(true);
                return false;
            }

            this.selectedMods = modsChooser.getSelectedMods();
        }

        if(!hasOptional){
            this.selectedMods = this.allMods;
        }

        for(Mod mod : this.selectedMods) {
            String file = mod.getFile();
            if (this.jsonVersion.getCaseAllFiles() == CaseType.upper) {
                file = file.substring(0, file.lastIndexOf("."))
                           .toUpperCase() + file.substring(file.lastIndexOf("."));
            } else if (this.jsonVersion.getCaseAllFiles() == CaseType.lower) {
                file = file.substring(0, file.lastIndexOf("."))
                           .toLowerCase() + file.substring(file.lastIndexOf("."));
            }

            this.installedMods.add(new DisableableMod(mod.getName(), mod.getVersion(), mod.isOptional(), file, Type.valueOf(Type.class, mod.getType().toString()), this.jsonVersion.getColour(mod.getColour()), mod.getDescription(), false, false));
        }

        if(this.reinstall && this.instance.hasCustomMods() && this.instance.getMinecraftVersion().equalsIgnoreCase(this.version.getMinecraftVersion().getVersion())){
            for(DisableableMod mod : this.instance.getCustomDisableableMods()){
                this.installedMods.add(mod);
            }
        }

        this.corrupt = true;
        if(!new File(App.settings.getTempDir(), this.pack.getSafeName() + "_" + this.version.getSafeVersion()).mkdirs()){
            LogManager.error("Error creating tmp directory");
        }

        this.backupFiles();
        this.mkdirs();
        this.addPercent(5);
        this.setMainClass();
        this.setExtraArgs();

        if(this.version.getMinecraftVersion().hasResources()){
            this.downloadResources();
            if(this.isCancelled()){
                return false;
            }
        }

        this.downloadLibs();
        if(this.isCancelled()){
            return false;
        }
        this.organizeLibs();
        if(this.isCancelled()){
            return false;
        }

        if(this.server){
            for(File file : this.serverLibs){
                file.mkdirs();
                Utils.copyFile(new File(App.settings.getLibrariesDir(), file.getName()), file, true);
            }
        }

        this.addPercent(5);

        File tmpJarDir = new File(App.settings.getTempDir(), this.pack.getSafeName() + "_" + this.version.getSafeVersion() + "_jar");

        if(this.server &&
           this.hasJarMods){

            this.fireTask(Language.INSTANCE.localize("server.extractingjar"));
            this.fireSubProgressUnknown();
            Utils.unzip(this.getMinecraftJar(), tmpJarDir);
        }

        if(!this.server &&
           this.hasJarMods &&
           !this.hasForge){

            this.deleteMetaInf();
        }

        this.addPercent(5);

        if(this.selectedMods.size() != 0){
            this.addPercent(40);
            this.fireTask(Language.INSTANCE.localize("instance.downloadingmods"));
            this.downloadMods();
            if(this.isCancelled()){
                return false;
            }
            this.addPercent(40);
            this.installMods();
        } else{
            this.addPercent(80);
        }

        if(this.isCancelled()){
            return false;
        }

        if(this.jsonVersion.shouldCaseAllFiles()){
            this.doCaseConversions();
        }

        if(this.server &&
           this.hasJarMods){

            this.fireTask(Language.INSTANCE.localize("server.zippingjar"));
            this.fireSubProgressUnknown();
            Utils.zip(tmpJarDir, this.getMinecraftJar());
        }

        if(this.extractedTexturePack){
            this.fireTask(Language.INSTANCE.localize("instance.zippingtexturepackfiles"));
            this.fireSubProgressUnknown();
            File texturePacks = new File(this.root, "texturepacks");
            if(!texturePacks.exists()){
                texturePacks.mkdir();
            }
            Utils.zip(new File(App.settings.getTempDir(), this.pack.getSafeName() + "_" + this.version.getSafeVersion() + "_tptmp"), new File(texturePacks, "TexturePack.zip"));
        }

        if(this.extractedResourcePack){
            this.fireTask(Language.INSTANCE.localize("instance.zippingresourcepackfiles"));
            this.fireSubProgressUnknown();
            File resourcePacks = new File(this.root, "resourcepacks");
            if(!resourcePacks.exists()){
                resourcePacks.mkdir();
            }
            Utils.zip(new File(App.settings.getTempDir(), this.pack.getSafeName() + "_" + this.version.getSafeVersion() + "_rptmp"), new File(resourcePacks, "ResourcePack.zip"));
        }

        if(this.isCancelled()){
            return false;
        }

        if(this.jsonVersion.hasActions()){
            for(Action action : this.jsonVersion.getActions()){
                action.execute(this);
            }
        }

        if(this.isCancelled()){
            return false;
        }

        if(!this.jsonVersion.hasNoConfigs()){
            this.configurePack();
        }

        if(App.settings.getCommonConfigsDir().listFiles().length > 0){
            Utils.copyDirectory(App.settings.getCommonConfigsDir(), this.root);
        }

        this.restoreFiles();

        if(this.server){
            File batFile = new File(this.root, "LaunchServer.bat");
            File shFile = new File(this.root, "LaunchServer.sh");
            String serverJar = this.getServerJar();

            Utils.replaceText(new File(App.settings.getLibrariesDir(), "LaunchServer.bat"), batFile, "%%SERVERJAR%%", serverJar);
            Utils.replaceText(new File(App.settings.getLibrariesDir(), "LaunchServer.sh"), shFile, "%%SERVERJAR%%", serverJar);

            batFile.setExecutable(true);
            shFile.setExecutable(true);
        }

        return true;
    }

    private void configurePack(){
        this.fireTask(Language.INSTANCE.localize("instance.downloadingconfigs"));
        File configs = new File(App.settings.getTempDir(), "Configs.zip");
        String path = "packs/" + this.pack.getSafeName() + "/versions/" + this.version.getVersion() + "/Configs.zip";
        Downloadable dl = new Downloadable(path, configs, null, this, true);
        this.totalBytes = dl.getFilesize();
        this.downloadedBytes = 0;
        dl.download(true);

        this.fireSubProgressUnknown();
        this.fireTask(Language.INSTANCE.localize("instance.extractingconfigs"));
        Utils.unzip(configs, this.root);
        Utils.delete(configs);
    }

    private void doCaseConversions(){
        File[] files;
        if(this.reinstall &&
           this.instance.getMinecraftVersion().equalsIgnoreCase(this.version.getMinecraftVersion().getVersion())){

            final List<String> customMods = this.instance.getCustomMods(Type.mods);
            files = this.mods.listFiles(
                                         new FilenameFilter() {
                                             @Override
                                             public boolean accept(File file, String s) {
                                                 return !customMods.contains(s);
                                             }
                                         }
            );
        } else{
            files = this.mods.listFiles();
        }

        for(File file : files){
            if(file.isFile() && (file.getName().matches(".*\\.(jar|litemod|zip)"))){
                if(this.jsonVersion.getCaseAllFiles() == CaseType.upper){
                    file.renameTo(new File(file.getParentFile(), file.getName().substring(0, file.getName().lastIndexOf(".")).toUpperCase() + file.getName().substring(file.getName().lastIndexOf("."), file.getName().length())));
                } else if(this.jsonVersion.getCaseAllFiles() == CaseType.lower){
                    file.renameTo(new File(file.getParentFile(), file.getName().toLowerCase()));
                }
            }
        }
    }

    private void deleteMetaInf(){
        File input = this.getMinecraftJar();
        File output = new File(App.settings.getTempDir(), this.pack.getSafeName() + "-minecraft.jar");
        try{
            JarInputStream jis = new JarInputStream(new FileInputStream(input));
            JarOutputStream jos = new JarOutputStream(new FileOutputStream(output));
            JarEntry entry;
            while((entry = jis.getNextJarEntry()) != null){
                if(entry.getName().contains("META-INF")){
                    continue;
                }

                jos.putNextEntry(entry);
                byte[] buffer = new byte[1024];
                int len;
                while((len = jis.read(buffer, 0, 1024)) > 0){
                    jos.write(buffer, 0, len);
                }
                jos.closeEntry();
            }

            jos.close();
            jis.close();

            if(!input.delete()){
                throw new IllegalStateException("This should never happen");
            }
            output.renameTo(input);
        } catch(Exception e){
            App.settings.logStackTrace(e);
        }
    }

    private void installMods(){
        for(Mod mod : this.selectedMods){
            if(!this.isCancelled()){
                this.fireTask(Language.INSTANCE.localize("common.installing") + " " + mod.getName());
                this.addPercent(this.selectedMods.size() / 40);
                mod.install(this);
            }
        }
    }

    private void organizeLibs(){
        List<String> libsAdded = new LinkedList<String>();
        this.fireTask(Language.INSTANCE.localize("instance.organisinglibraries"));
        this.fireSubProgressUnknown();

        File bin = new File(this.root, "bin");
        File natives = new File(bin, "natives");

        if(!this.server){
            for(String libFile : this.forgeLibs){
                File lib = new File(App.settings.getLibrariesDir(), libFile);

                if(lib.exists()){
                    Utils.copyFile(lib, bin);
                } else{
                    LogManager.error("Cannot install instance because the library file " + lib.getAbsolutePath() + "  wasn't found");
                    this.cancel(true);
                    return;
                }

                libsAdded.add(lib.getName().substring(0, lib.getName().lastIndexOf("-")));
            }

            for(com.atlauncher.data.mojang.Library lib : this.version.getMinecraftVersion().getMojangVersion().getLibraries()){
                if(lib.shouldInstall()){
                    if(libsAdded.contains(lib.getFile().getName().substring(0, lib.getFile().getName().lastIndexOf("-")))){
                        continue;
                    }

                    if(lib.getFile().exists()){
                        if(lib.shouldExtract()){
                            Utils.unzip(lib.getFile(), natives, lib.getExtractRule());
                        } else{
                            Utils.copyFile(lib.getFile(), bin);
                        }
                    } else{
                        LogManager.error(
                                                "Cannot install instance because the library file " + lib.getFile()
                                                                                                         .getAbsolutePath() + " wasn't found"
                        );
                        this.cancel(true);
                        return;
                    }
                }
            }
        }

        File from, to;
        boolean withFilename = false;
        if(this.server){
            from = new File(App.settings.getJarsDir(), "minecraft_server." + this.version.getMinecraftVersion().getVersion() + ".jar");
            to = root;
        } else{
            from = new File(App.settings.getJarsDir(), this.version.getMinecraftVersion().getVersion() + ".jar");
            to = new File(bin, "minecraft.jar");
            withFilename = true;
        }

        if(from.exists()){
            Utils.copyFile(from, to, withFilename);
        } else{
            LogManager.error("Cannot install instance because the library file " + from.getAbsolutePath() + " wasn't found");
            this.cancel(true);
            return;
        }
        this.fireSubProgress(-1);
    }

    private void downloadLibs(){
        this.fireTask(Language.INSTANCE.localize("instance.downloadinglibraries"));
        this.fireSubProgressUnknown();
        List<Downloadable> downloads = this.getLibraries();
        this.totalBytes = this.downloadedBytes = 0;

        for(Downloadable download : downloads){
            if(download.needToDownload()){
                totalBytes += download.getFilesize();
            }
        }

        fireSubProgress(0);
        for(final Downloadable download : downloads){
            this.executor.execute(
                                         new Runnable() {
                                             @Override
                                             public void run() {
                                                 if(download.needToDownload()){
                                                     fireTask(Language.INSTANCE.localize("common.downloading") + " " + download.getFilename());
                                                     download.download(true);
                                                 }
                                             }
                                         }
            );
        }
        fireSubProgress(-1);
    }

    private void downloadMods(){
        this.fireSubProgressUnknown();
        List<Downloadable> downloads = this.getMods();
        this.totalBytes = this.downloadedBytes = 0;

        for(Downloadable download : downloads){
            if(download.needToDownload()){
                this.totalBytes += download.getFilesize();
            }
        }

        this.fireSubProgress(0);
        for(final Downloadable download : downloads){
            this.executor.execute(
                                         new Runnable() {
                                             @Override
                                             public void run() {
                                                 if(download.needToDownload()){
                                                     download.download(true);
                                                 }
                                             }
                                         }
            );
        }
        this.fireSubProgress(-1);

        for(Mod mod : this.selectedMods){
            if(!this.isCancelled()){
                this.fireTask(Language.INSTANCE.localize("common.downloading") + " " + (mod.isFilePattern() ? mod.getName() : mod.getFile()));
                mod.download(this);
                this.fireSubProgress(-1);
            }
        }
    }

    private void downloadResources(){
        this.fireTask(Language.INSTANCE.localize("instance.downloadingresources"));
        this.fireSubProgressUnknown();
        List<Downloadable> downloads = this.getResources();
        this.totalBytes = this.downloadedBytes = 0;

        for(Downloadable download : downloads){
            if(download.needToDownload()){
                totalBytes += download.getFilesize();
            }
        }

        fireSubProgress(0);
        for(final Downloadable download : downloads){
            this.executor.execute(
                                         new Runnable() {
                                             @Override
                                             public void run() {
                                                 if(download.needToDownload()){
                                                     fireTask(Language.INSTANCE.localize("common.downloading") + download.getFilename());
                                                     download.download(true);
                                                 } else{
                                                     download.copyFile();
                                                 }
                                             }
                                         }
            );
        }
        fireSubProgress(-1);
    }

    private void setExtraArgs(){
        if(this.jsonVersion.hasExtraArguments()){
            if(!this.jsonVersion.getExtraArguments().hasDepends() &&
               !this.jsonVersion.getExtraArguments().hasDependsGroup()){

                this.extraArgs = this.jsonVersion.getExtraArguments().getArguments();
            } else if(this.jsonVersion.getExtraArguments().hasDepends()){
                String depends = this.jsonVersion.getExtraArguments().getDepends();
                boolean found = false;

                for(Mod mod : this.selectedMods){
                    if(mod.getName().equals(depends)){
                        found = true;
                        break;
                    }
                }

                if(found){
                    this.extraArgs = this.jsonVersion.getExtraArguments().getArguments();
                }
            } else if(this.jsonVersion.getExtraArguments().hasDependsGroup()){
                String depends = this.jsonVersion.getMainClass().getDependsGroup();
                boolean found = false;

                for(Mod mod : this.selectedMods){
                    if(!mod.hasGroup()){
                        continue;
                    }

                    if(mod.getGroup().equals(depends)){
                        found = true;
                        break;
                    }
                }

                if(found){
                    this.extraArgs = this.jsonVersion.getExtraArguments().getArguments();
                }
            }
        }
    }

    private void setMainClass(){
        if(this.jsonVersion.hasMainClass()){
            if(!this.jsonVersion.getMainClass().hasDepends() &&
               !this.jsonVersion.getMainClass().hasDependsGroup()){

                this.mainClass = this.jsonVersion.getMainClass().getMainClass();
            } else if(this.jsonVersion.getMainClass().hasDepends()){
                String depends = this.jsonVersion.getMainClass().getDepends();
                boolean found = false;

                for(Mod mod : this.selectedMods){
                    if(mod.getName().equals(depends)){
                        found = true;
                        break;
                    }
                }

                if(found){
                    this.mainClass = this.jsonVersion.getMainClass().getMainClass();
                }
            } else if(this.jsonVersion.getMainClass().hasDependsGroup()){
                String depends = this.jsonVersion.getMainClass().getDependsGroup();
                boolean found = false;

                for(Mod mod : this.selectedMods){
                    if(!mod.hasGroup()){
                        continue;
                    }

                    if(mod.getGroup().equals(depends)){
                        found = true;
                        break;
                    }
                }

                if(found){
                    this.mainClass = this.jsonVersion.getMainClass().getMainClass();
                }
            }

            if(this.mainClass == null){
                this.mainClass = this.version.getMinecraftVersion().getMojangVersion().getMainClass();
            }
        }
    }

    private void restoreFiles(){
        File tmp = new File(App.settings.getTempDir(), this.pack.getSafeName() + "_" + this.version.getSafeVersion());
        File mods = new File(this.root, "mods");

        if(this.savedReis){
            Utils.copyDirectory(new File(tmp, "rei_minimap"), new File(mods, "rei_minimap"));
        }

        if(this.savedZans){
            Utils.copyDirectory(new File(tmp, "VoxelMods"), new File(mods, "VoxelMods"));
        }

        if(this.savedNEIConfig){
            Utils.copyFile(new File(tmp, "NEI.cfg"), new File(new File(this.root, "config"), "NEI.cfg"), true);
        }

        if(this.savedOptions){
            Utils.copyFile(new File(tmp, "options.txt"), new File(this.root, "options.txt"), true);
        }

        if(this.savedServers){
            Utils.copyFile(new File(tmp, "servers.dat"), new File(this.root, "servers.dat"), true);
        }

        if(this.savedPortalGunSounds){
            Utils.copyFile(new File(tmp, "PortalGunSounds.pak"), new File(mods, "PortalGunSounds.pak"), true);
        }
    }

    private void backupFiles(){
        File tmp = new File(App.settings.getTempDir(), this.pack.getSafeName() + "_" + this.version.getSafeVersion());
        File mods = new File(this.root, "mods");

        File reis = new File(mods, "rei_minimap");
        if(reis.exists() && reis.isDirectory()){
            this.savedReis = Utils.copyDirectory(reis, tmp, true);
        }

        File zans = new File(mods, "VoxelMods");
        if(zans.exists() && zans.isDirectory()){
            this.savedZans = Utils.copyDirectory(zans, tmp, true);
        }

        File neiConfig = new File(new File(this.root, "config"), "NEI.cfg");
        if(neiConfig.exists() && neiConfig.isFile()){
            this.savedNEIConfig = Utils.copyFile(neiConfig, tmp);
        }

        File options = new File(this.root, "options.txt");
        if(options.exists() && options.isFile()){
            this.savedOptions = Utils.copyFile(options, tmp);
        }

        File servers = new File(this.root, "servers.dat");
        if(servers.exists() && servers.isFile()){
            this.savedServers = Utils.copyFile(servers, tmp);
        }

        File pgSounds = new File(mods, "PortalGunSounds.pak");
        if(pgSounds.exists() && pgSounds.isFile()){
            this.savedPortalGunSounds = Utils.copyFile(pgSounds, tmp);
        }
    }

    private void addPercent(int perc){
        this.percent = this.percent + perc;
        if(this.percent > 100){
            this.percent = 100;
        }

        this.firePropertyChange("progress", null, this.percent);
    }

    private void fireSubProgress(int perc){
        if(perc > 100){
            perc = 100;
        }

        this.firePropertyChange("subprogress", null, perc);
    }

    public void fireSubProgressUnknown(){
        this.firePropertyChange("subprogressint", null, null);
    }

    private void fireSubProgress(int perc, String paint){
        if(perc > 100){
            perc = 100;
        }

        String[] info = new String[2];
        info[0] = String.valueOf(perc);
        info[1] = paint;
        this.firePropertyChange("subprogress", null, info);
    }

    public void fireTask(String name){
        this.firePropertyChange("doing", null, name);
    }

    private List<Mod> sortMods(List<Mod> original){
        List<Mod> modz = new LinkedList<Mod>(original);
        for(Mod mod : original){
            if(mod.isOptional()){
                if(mod.hasLinked()){
                    for(Mod comp : original){
                        if(comp.getName().equalsIgnoreCase(mod.getLinked())){
                            modz.remove(mod);
                            modz.add(modz.indexOf(comp) + 1, mod);
                        }
                    }
                }
            }
        }

        List<Mod> mods = new LinkedList<Mod>();
        for(Mod mod : modz){
            if(!mod.isOptional()){
                mods.add(mod);
            }
        }

        for(Mod mod : modz){
            if(!mods.contains(mod)){
                mods.add(mod);
            }
        }

        return mods;
    }

    @Override
    protected Boolean doInBackground()
    throws Exception {
        LogManager.info("Started install of " + this.pack.getName() + " - " + this.version);
        return this.installUsingJson();
    }
}