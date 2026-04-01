package extension.tools;

import extension.GPresets;
import extension.parsers.HWiredVariable;
import extension.tools.importutils.*;
import extension.tools.postconfig.ItemSource;
import extension.tools.postconfig.PostConfig;
import extension.tools.presetconfig.PresetConfig;
import extension.tools.presetconfig.ads_bg.PresetAdsBackground;
import extension.tools.presetconfig.binding.PresetWiredFurniBinding;
import extension.tools.presetconfig.furni.PresetFurni;
import extension.tools.presetconfig.furni.PresetWallFurni;
import extension.tools.presetconfig.wired.*;
import furnidata.FurniDataTools;
import game.BCCatalog;
import game.FloorState;
import game.Inventory;
import gearth.extensions.parsers.HFloorItem;
import gearth.extensions.parsers.HInventoryItem;
import gearth.extensions.parsers.HPoint;
import gearth.extensions.parsers.HWallItem;
import gearth.protocol.HMessage;
import gearth.protocol.HPacket;
import utils.StateExtractor;
import utils.Utils;
import utils.WallPosition;

import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class GPresetImporter {

    private final Object lock = new Object();

    private GPresets extension = null;
    private List<String> needVariableIds = new ArrayList<>();
    private int variablesProcessed;
    private int variablesToProcess;


    public enum BuildingImportState {
        NONE,

        // select a location ingame that will be used to move furniture to that need state adjustments
        // preferably your character stands next to it (for manipulating states for furni like switches)
        // the location may not contain any furniture
        AWAITING_UNOCCUPIED_SPACE,
        AWAITING_USE_ROOM_FURNI_RECT1,
        AWAITING_USE_ROOM_FURNI_RECT2,

        AWAITING_ROOT_LOCATION,
        AWAITING_UNOCCUPIED_SPACE_STATE_CHANGER,

        ADD_UNSTACKABLES,   // add directly to correct location

        // find a position for the magic stack tile

        ADD_FURNITURE,      // add to placeholder location (magic stack tile)

        // check if all furniture were placed

        SETUP_ADS,
        SETUP_WIRED,
        MOVE_FURNITURE      // move to correct location
    }

    private PresetConfig presetConfig = null;
    private PostConfig postConfig = new PostConfig();


    private PresetConfig workingPresetConfig = null;
//    private PostConfig workingPostConfig = null;


    private Map<Integer, Integer> realFurniIdMap = null;
    private Map<String, String> realVariableIdMap = new HashMap<>();

    // expect furni drops on location described by key(string) -> "x|y|typeId"
    private Map<String, LinkedList<Integer>> expectFurniDrops = null;
    private BuildingImportState state = BuildingImportState.NONE;


    private volatile int mainStackTile = -1;
    private volatile int mainStackDimensions = 0;

    private HPoint useRoomFurniRectCorner1 = null;
    private HPoint useRoomFurniRectCorner2 = null;
    private HPoint reservedSpace = null;
    private HPoint rootLocation = null;
    private HPoint stackTileLocation = null;
    private HPoint stateChangerLocation = null;

    private List<StackTileInfo> allAvailableStackTiles = null;

    private List<HFloorItem> roomItemsList = new ArrayList<>();
//    private HPoint originalStackTileLocation = new HPoint(0, 0); // move back after movements

    private int heightOffset = 0;

    public GPresetImporter(GPresets extension) {
        this.extension = extension;

        extension.intercept(HMessage.Direction.TOSERVER, "Chat", this::onChat);
        extension.intercept(HMessage.Direction.TOSERVER, "MoveAvatar", this::moveAvatar);

        extension.intercept(HMessage.Direction.TOCLIENT, "ObjectAdd", this::onObjectAdd);
        extension.intercept(HMessage.Direction.TOCLIENT, "ObjectUpdate", this::onObjectAdd);
        extension.intercept(HMessage.Direction.TOCLIENT, "WiredAllVariablesDiffs", this::onWiredAllVariables);

        extension.intercept(HMessage.Direction.TOSERVER, "PlaceObject", this::maybeBlockPlacements);
        extension.intercept(HMessage.Direction.TOSERVER, "BuildersClubPlaceRoomItem", this::maybeBlockPlacements);

        extension.intercept(HMessage.Direction.TOSERVER, "MoveObject", this::blockFurniAdjustments);
        extension.intercept(HMessage.Direction.TOSERVER, "UseFurniture", this::blockFurniAdjustments);
        extension.intercept(HMessage.Direction.TOSERVER, "SetCustomStackingHeight", this::blockFurniAdjustments);
        extension.intercept(HMessage.Direction.TOSERVER, "UpdateCondition", this::blockFurniAdjustments);
        extension.intercept(HMessage.Direction.TOSERVER, "UpdateTrigger", this::blockFurniAdjustments);
        extension.intercept(HMessage.Direction.TOSERVER, "UpdateAction", this::blockFurniAdjustments);
        extension.intercept(HMessage.Direction.TOSERVER, "UpdateAddon", this::blockFurniAdjustments);
        extension.intercept(HMessage.Direction.TOSERVER, "UpdateSelector", this::blockFurniAdjustments);

        extension.intercept(HMessage.Direction.TOCLIENT, "WiredSaveSuccess", this::wiredSaved);
    }

    private void onWiredAllVariables(HMessage hMessage) {
        if (state == BuildingImportState.SETUP_WIRED) {
            HPacket packet = hMessage.getPacket();
            int _allVariablesHash = packet.readInteger();
            boolean isLastChunk = packet.readBoolean();

            int removedVariablesLength = packet.readInteger();
            for(int i = 0; i < removedVariablesLength; i++) {
                packet.readLong();
            }

            HashSet<HWiredVariable> variables = new HashSet<>();

            int count = packet.readInteger();
            for(int i = 0; i < count; i++) {
                int addedOrUpdated = packet.readInteger();
                variables.add(new HWiredVariable(packet));
            }

            if(workingPresetConfig != null && workingPresetConfig.getPresetWireds() != null) {
                HashMap<String, String> map = workingPresetConfig.getPresetWireds().getVariablesMap();

                for(HWiredVariable newVar : variables) {
                    Optional<Map.Entry<String, String>> op = map.entrySet().stream().filter(k -> k.getKey().equals(newVar.name)).findFirst();
                    op.ifPresent(stringStringEntry -> realVariableIdMap.put(stringStringEntry.getValue(), newVar.id));
                }

                for (PresetWiredVariable origVar : workingPresetConfig.getPresetWireds().getVariables()) {
                    for(HWiredVariable newVar : variables) {
                        if(newVar.name.equals(origVar.getStringConfig())) {
                            realVariableIdMap.put(origVar.variableId, newVar.id);
                        }
                    }
                }
            }

            for(String id : realVariableIdMap.keySet()) {
                needVariableIds.remove(id);
            }

            if(needVariableIds.size() == 0) {
                wiredVariableConfirmation.release();
            }

        }
    }

    private void blockFurniAdjustments(HMessage message) {
        synchronized (lock) {
            if (state == BuildingImportState.SETUP_WIRED || state == BuildingImportState.MOVE_FURNITURE) {
                message.setBlocked(true);
                extension.sendVisualChatInfo("Don't adjust furniture while the importer is active!");
            }
        }
    }

    private void maybeBlockPlacements(HMessage hMessage) {
        synchronized (lock) {
            if (state != BuildingImportState.NONE && state != BuildingImportState.MOVE_FURNITURE) {
                hMessage.setBlocked(true);
                extension.sendVisualChatInfo("Can't drop furniture while the importer is active, please await the procedure or abort");
            }
        }
    }

    private boolean isPlacing() {
        return state == BuildingImportState.ADD_UNSTACKABLES || state == BuildingImportState.ADD_FURNITURE;
    }

    private void onObjectAdd(HMessage hMessage) {
        synchronized (lock) {
            if (isPlacing()) {
                HFloorItem item = new HFloorItem(hMessage.getPacket());
//                String classname = extension.getFurniDataTools().getFloorItemName(item.getTypeId());
                String dropKey = String.format("%d|%d|%d",
                        item.getTile().getX(), item.getTile().getY(), item.getTypeId());

                LinkedList<Integer> awaitMatchingDropIds = expectFurniDrops.get(dropKey);
                if (awaitMatchingDropIds != null && awaitMatchingDropIds.size() > 0) {
                    int assignedFurniId = awaitMatchingDropIds.pollFirst();
                    realFurniIdMap.put(assignedFurniId, item.getId());

//                    if (classname != null && classname.equals("ads_background")) {
//                        PresetAdsBackground ads = workingPresetConfig.getAdsBackgrounds().stream().filter(a -> a.getFurniId() == assignedFurniId).findFirst().orElse(null);
//                        if (ads != null) {
//
//                        }
//                    }

                    if (awaitMatchingDropIds.size() == 0) {
                        expectFurniDrops.remove(dropKey);
                    }
                }
            }
        }
    }

    public boolean isReady() {
        boolean hasFloorItems = presetConfig != null && !presetConfig.getFurniture().isEmpty();
        boolean hasWallItems = presetConfig != null && presetConfig.getWallFurniture() != null && !presetConfig.getWallFurniture().isEmpty();
        boolean bcReady = !hasWallItems || (extension.getCatalog() != null && extension.getCatalog().getState() == BCCatalog.CatalogState.COLLECTED);
        return extension.getFloorState().inRoom() && extension.furniDataReady() &&
                extension.getInventory().getState() == Inventory.InventoryState.LOADED &&
                (!hasFloorItems || extension.stackTile() != null) &&
                presetConfig != null && bcReady &&
                extension.getPermissions().canMoveFurni() && (!extension.shouldExportWired() || extension.getPermissions().canModifyWired());
    }

    private void onChat(HMessage hMessage) {
        String text = hMessage.getPacket().readString();
        synchronized (lock) {
            if (text.startsWith(":ip") || text.startsWith(":importpreset")) {
                hMessage.setBlocked(true);

                if (state != BuildingImportState.NONE) {
                    extension.sendVisualChatInfo("Already importing preset.. finish up or abort first");
                }
                else if (!isReady()) {
                    if (presetConfig == null) {
                        extension.sendVisualChatInfo("ERROR: select the preset first");
                    }
                    else {
                        extension.sendVisualChatInfo("ERROR: extension not fully initialized yet");
                    }
                }
                else {
                    prepare();
                    try {
                        String[] split = text.split(" ", 2);
                        String[] loc = split[1].split(", ?");
                        int rootX = Integer.parseInt(loc[0]);
                        int rootY = Integer.parseInt(loc[1]);
                        rootLocation = new HPoint(rootX, rootY);
                    }
                    catch (Exception ignore) {}

                    if (rootLocation != null && state == BuildingImportState.AWAITING_ROOT_LOCATION
                            && workingPresetConfig != null && workingPresetConfig.getFurniture().isEmpty()) {
                        startWallOnlyImport();
                    }
                }

            }

            if (text.equals(":abort") || text.equals(":a")) {
                hMessage.setBlocked(true);

                if (state != BuildingImportState.NONE) {
                    state = BuildingImportState.NONE;
                    extension.sendVisualChatInfo("Successfully aborted importing");
                }
            }

            if(text.equals("all") && (state.equals(BuildingImportState.AWAITING_USE_ROOM_FURNI_RECT1) ||
                    state.equals(BuildingImportState.AWAITING_USE_ROOM_FURNI_RECT2))) {
                hMessage.setBlocked(true);

                useRoomFurniRectCorner1 = new HPoint(0, 0);
                useRoomFurniRectCorner2 = new HPoint(100, 100);

                if (state != BuildingImportState.AWAITING_UNOCCUPIED_SPACE) {
                    state = BuildingImportState.AWAITING_UNOCCUPIED_SPACE;
                    extension.sendVisualChatInfo("Select where the stack tile should be placed (empty area)");
                }
            }
        }
    }

    private void prepare() {
        if (presetConfig != null) {
//            workingPostConfig = postConfig;
            workingPresetConfig = new PresetConfig(presetConfig.toJsonObject());

            realFurniIdMap = workingPresetConfig.applyPostConfig(postConfig);
            expectFurniDrops = new HashMap<>();
            inventoryCache.clear();
            wallInventoryCache.clear();
            rootLocation = null;
            stateChangerLocation = null;

            boolean wallOnly = workingPresetConfig.getFurniture().isEmpty();

            if (wallOnly) {
                state = BuildingImportState.AWAITING_ROOT_LOCATION;
                extension.sendVisualChatInfo("Select where the preset should be imported");
                return;
            }

            FurniDataTools furniData = extension.getFurniDataTools();
            FloorState floorState = extension.getFloorState();
            roomItemsList = new ArrayList<>(floorState.getItems());
            List<FurniDropInfo> fakeDropInfo = new ArrayList<>();
            for (PresetFurni f : workingPresetConfig.getFurniture()) {
                fakeDropInfo.add(new FurniDropInfo(-1, -1, furniData.getFloorTypeId(f.getClassName()), postConfig.getItemSource(), -1));
            }
            Map<String, Integer> missing = AvailabilityChecker.missingItems(fakeDropInfo, extension.getInventory(), furniData, floorState, extension.useRoomFurni());
            if (missing == null) {
                extension.sendVisualChatInfo("ERROR: Inventory, catalog or furnidata is unavailable");
            }
            else {
                if (!missing.isEmpty()) {
                    if (extension.allowIncompleteBuilds()) {
                        extension.sendVisualChatInfo("Some items were not available, building anyways..");
                    }
                    else {
                        extension.sendVisualChatInfo("ERROR: Some items were not available, check the availability first!");
                        return;
                    }
                }
                mainStackTile = extension.stackTile().getId();

                allAvailableStackTiles = new ArrayList<>();
                Set<String> allStacktileClasses = Arrays.stream(StackTileSetting.values()).map(StackTileSetting::getClassName).collect(Collectors.toSet());
                allStacktileClasses.forEach(c -> {
                    List<HFloorItem> stackTiles = extension.getFloorState().getItemsFromType(furniData, c);
                    if (stackTiles.size() > 0) {
                        HFloorItem stackTile = stackTiles.get(0);
                        allAvailableStackTiles.add(new StackTileInfo(
                                stackTile.getId(),
                                stackTile.getTile(),
                                stackTile.getFacing().ordinal(),
                                StackTileSetting.fromClassName(c).getDimension()
                        ));
                    }
                });

                if (!allAvailableStackTiles.isEmpty()) {
                    extension.getLogger().log(String.format("Detected %d available types of stacktiles", allAvailableStackTiles.size()), "green");
                }

//                originalStackTileLocation = extension.stackTile().getTile();
                mainStackDimensions = extension.getStackTileSetting().getDimension();

                if(extension.useRoomFurni()) {
                    state = BuildingImportState.AWAITING_USE_ROOM_FURNI_RECT1;
                    extension.sendVisualChatInfo("You've enabled use Room Furni, please select the start rectangle around the furni you want to use as backup or type 'all'");
                } else {
                    state = BuildingImportState.AWAITING_UNOCCUPIED_SPACE;
                    extension.sendVisualChatInfo("Select where the stack tile should be placed (empty area)");
                }
            }

        }
        else {
            extension.sendVisualChatInfo("ERROR: No preset selected!");
        }
    }

    private void moveAvatar(HMessage hMessage) {
        synchronized (lock) {
            boolean startAddingFurni = false;

            if (state == BuildingImportState.AWAITING_USE_ROOM_FURNI_RECT1) {
                hMessage.setBlocked(true);
                useRoomFurniRectCorner1 = new HPoint(
                        hMessage.getPacket().readInteger(),
                        hMessage.getPacket().readInteger()
                );

                if (useRoomFurniRectCorner2 == null) {
                    state = BuildingImportState.AWAITING_USE_ROOM_FURNI_RECT2;
                    extension.sendVisualChatInfo("Select the end of the rectangle where the furni is");
                }
            } else if (state == BuildingImportState.AWAITING_USE_ROOM_FURNI_RECT2) {
                hMessage.setBlocked(true);
                useRoomFurniRectCorner2 = new HPoint(
                        hMessage.getPacket().readInteger(),
                        hMessage.getPacket().readInteger()
                );

                if (reservedSpace == null) {
                    state = BuildingImportState.AWAITING_UNOCCUPIED_SPACE;
                    extension.sendVisualChatInfo("Select where the stack tile should be placed (empty area)");
                }
            } else if (state == BuildingImportState.AWAITING_UNOCCUPIED_SPACE) {
                hMessage.setBlocked(true);
                int sx = hMessage.getPacket().readInteger();
                int sy = hMessage.getPacket().readInteger();
                reservedSpace = new HPoint(sx, sy);
                stackTileLocation = new HPoint(sx, sy);

                if (rootLocation == null) {
                    state = BuildingImportState.AWAITING_ROOT_LOCATION;
                    extension.sendVisualChatInfo("Select where the preset should be imported");
                }
                else {
                    startAddingFurni = true;
                }
            }
            else if (state == BuildingImportState.AWAITING_ROOT_LOCATION) {
                hMessage.setBlocked(true);
                int x = hMessage.getPacket().readInteger();
                int y = hMessage.getPacket().readInteger();
                rootLocation = new HPoint(
                        x,
                        y//,
//                        PresetUtils.lowestFloorPoint(extension.getFloorState(), workingPresetConfig, new HPoint(x, y))
                );

                if (!extension.getPermissions().canModifyWired() && presetNeedsStateChanges()) {
                    state = BuildingImportState.AWAITING_UNOCCUPIED_SPACE_STATE_CHANGER;
                    extension.sendVisualChatInfo("No wired permissions - select a tile for state changing furni (empty area near you)");
                } else {
                    startAddingFurni = true;
                }
            }
            else if (state == BuildingImportState.AWAITING_UNOCCUPIED_SPACE_STATE_CHANGER) {
                hMessage.setBlocked(true);
                int scx = hMessage.getPacket().readInteger();
                int scy = hMessage.getPacket().readInteger();
                stateChangerLocation = new HPoint(scx, scy);
                startAddingFurni = true;
            }

            if (startAddingFurni) {
                if (workingPresetConfig.getFurniture().isEmpty()) {
                    startWallOnlyImport();
                } else {
                    heightOffset = PresetUtils.lowestFloorPoint(extension.getFloorState(), workingPresetConfig, rootLocation);

                    state = BuildingImportState.ADD_UNSTACKABLES;
                    new Thread(this::addUnstackables).start();
                    extension.sendVisualChatInfo("Adding furniture...");
                }
            }
        }
    }

    private void startWallOnlyImport() {
        state = BuildingImportState.MOVE_FURNITURE;
        new Thread(() -> {
            placeWallItems();
            synchronized (lock) {
                if (state == BuildingImportState.MOVE_FURNITURE) {
                    state = BuildingImportState.NONE;
                    extension.sendVisualChatInfo("Imported the preset successfully");
                    extension.getLogger().log("Finished importing the preset!", "green");
                }
            }
        }).start();
        extension.sendVisualChatInfo("Placing wall items...");
    }

    Map<Integer, LinkedList<HInventoryItem>> inventoryCache = new HashMap<>();
    Map<String, LinkedList<HInventoryItem>> wallInventoryCache = new HashMap<>();

    private boolean isStackTileClass(String className) {
        return className != null && className.startsWith("tile_stackmagic");
    }

    private void dropFurni(FurniDropInfo dropInfo) {
        FurniDataTools furniData = extension.getFurniDataTools();

        int typeId = dropInfo.getTypeId();
        String className = furniData.getFloorItemName(typeId);

        ItemSource source = dropInfo.getItemSource();

        Inventory inventory = extension.getInventory();

        int offerId = furniData.getFloorItemDetails(className).bcOfferId;
        if (!inventoryCache.containsKey(typeId)) {
            inventoryCache.put(typeId, new LinkedList<>(inventory.getFloorItemsByType(typeId)));
        }
        LinkedList<HInventoryItem> inventoryItems = inventoryCache.get(typeId);

        boolean useBC = source == ItemSource.ONLY_BC || (source == ItemSource.PREFER_BC && offerId != -1)
                || ( source == ItemSource.PREFER_INVENTORY && inventoryItems.isEmpty());

        boolean useRoomFurni = extension.useRoomFurni();

        if (useBC) {
            if (offerId == -1) {
                if (!extension.allowIncompleteBuilds()) {
                    state = BuildingImportState.NONE;
                    extension.sendVisualChatInfo(String.format("ERROR: Couldn't find the item '%s' in BC warehouse.. aborting", className));
                }
                else {
                    extension.sendVisualChatInfo(String.format("Couldn't find the item '%s' in BC warehouse.. continuing", className));
                }
                return;
            }
            extension.sendToServer(new HPacket(
                    "BuildersClubPlaceRoomItem",
                    HMessage.Direction.TOSERVER,
                    -1,
                    offerId,
                    "",
                    dropInfo.getX(),
                    dropInfo.getY(),
                    dropInfo.getRotation()
            ));

            Utils.sleep(230);
        }
        else {
            if (inventoryItems.isEmpty() && !useRoomFurni) {
                if (!extension.allowIncompleteBuilds()) {
                    state = BuildingImportState.NONE;
                    extension.sendVisualChatInfo(String.format("ERROR: Couldn't find '%s' in inventory.. aborting", className));
                }
                else {
                    extension.sendVisualChatInfo(String.format("Couldn't find '%s' in inventory.. continuing", className));
                }
                return;
            }

            if(useRoomFurni && inventoryItems.isEmpty()) {
                HFloorItem floorItem = roomItemsList
                        .stream()
                        .filter(furni -> furni.getTypeId() == dropInfo.getTypeId() && isInsideUseRoomRectangle(furni.getTile().getX(), furni.getTile().getY()))
                        .findFirst()
                        .orElse(null);

                if (floorItem != null) {
                    roomItemsList.removeIf(item -> item.getId() == floorItem.getId());

                    realFurniIdMap.put(dropInfo.getTypeId(), floorItem.getId());

                    HPacket packet = new HPacket("MoveObject", HMessage.Direction.TOSERVER, floorItem.getId(), dropInfo.getX(), dropInfo.getY(), dropInfo.getRotation());
                    extension.sendToServer(packet);

                    Utils.sleep(600);
                }
                else if (!extension.allowIncompleteBuilds()) {
                    state = BuildingImportState.NONE;
                    extension.sendVisualChatInfo(String.format("ERROR: Couldn't find '%s' in inventory or room.. aborting", className));
                }
                else {
                    extension.sendVisualChatInfo(String.format("Couldn't find '%s' in inventory or room.. continuing", className));
                }

                return;
            }

            HInventoryItem item = inventoryItems.pollFirst();
            extension.sendToServer(new HPacket(
                    "PlaceObject",
                    HMessage.Direction.TOSERVER,
                    String.format("-%d %d %d %d",
                            item.getId(),
                            dropInfo.getX(),
                            dropInfo.getY(),
                            dropInfo.getRotation()
                    )
            ));

//            Utils.sleep(600);
            Utils.sleep(150);
        }
    }

    private boolean isInsideUseRoomRectangle(int px, int py) {
        if (useRoomFurniRectCorner1 == null || useRoomFurniRectCorner2 == null) return false;

        int x1 = useRoomFurniRectCorner1.getX();
        int y1 = useRoomFurniRectCorner1.getY();
        int x2 = useRoomFurniRectCorner2.getX();
        int y2 = useRoomFurniRectCorner2.getY();

        int minX = Math.min(x1, x2);
        int maxX = Math.max(x1, x2);
        int minY = Math.min(y1, y2);
        int maxY = Math.max(y1, y2);

        return px >= minX && px <= maxX && py >= minY && py <= maxY;
    }

    private void moveFurni(int furniId, int x, int y, int rot, boolean moveStackTile, double height) {
        if (moveStackTile) {
            StackTileInfo stackInfo = StackTileUtils.findBestDropLocation(x, y, allAvailableStackTiles, extension.getFloorState());
            if (stackInfo != null) {
                moveFurni(stackInfo.getFurniId(), stackInfo.getLocation().getX(), stackInfo.getLocation().getY(),
                        stackInfo.getRotation(), false, -1);
                if (height != -1) {
                    extension.sendToServer(new HPacket(
                            "SetCustomStackingHeight",
                            HMessage.Direction.TOSERVER,
                            stackInfo.getFurniId(),
                            ((int) (height * 100)) + heightOffset * 100
                    ));

                    Utils.sleep(40);
                }
            }
        }

        extension.sendToServer(new HPacket(
                "MoveObject",
                HMessage.Direction.TOSERVER,
                furniId,
                x, y, rot
        ));

        Utils.sleep(60);
    }

    private boolean presetNeedsStateChanges() {
        if (workingPresetConfig == null) return false;
        for (PresetFurni f : workingPresetConfig.getFurniture()) {
            if (f.getState() != null) return true;
        }
        return false;
    }

    private void attemptSetState(int furniId, String targetState) {
        FloorState floor = extension.getFloorState();
        FurniDataTools furniData = extension.getFurniDataTools();
        if (floor == null || furniData == null || floor.furniFromId(furniId) == null) return;

        HFloorItem item = floor.furniFromId(furniId);
        boolean isStackable = furniData.isStackable(furniData.getFloorItemName(item.getTypeId()));
        HPoint originalLocation = item.getTile();
        int originalRotation = item.getFacing().ordinal();

        if (extension.getPermissions().canModifyWired()) {
            // Set state using the @state variable
            extension.sendToServer(new HPacket("WiredSetObjectVariableValue", HMessage.Direction.TOSERVER, 0, furniId, "-110", Integer.parseInt(targetState)));
            Utils.sleep(Math.max(extension.getSafeFeedbackTimeout(), 60));
        } else {
            String currentState = StateExtractor.stateFromItem(item);
            if (currentState == null || currentState.equals(targetState)) {
                return;
            }

            HPoint stateLocation = stateChangerLocation != null ? stateChangerLocation : stackTileLocation;
            if (stateLocation == null) return;

            synchronized (lock) {
                if (state != BuildingImportState.NONE) {
                    moveFurni(furniId, stateLocation.getX(), stateLocation.getY(), 0, false, -1);
                }
            }

            String newState = "-1";

            int i = 0;
            while (state != BuildingImportState.NONE && newState != null && !currentState.equals(newState) && !newState.equals(targetState) && i < 20) {
                currentState = newState;

                extension.sendToServer(new HPacket("UseFurniture", HMessage.Direction.TOSERVER, furniId, 0));
                Utils.sleep(Math.max(extension.getSafeFeedbackTimeout(), 60));

                HFloorItem itemNew = floor.furniFromId(furniId);
                newState = StateExtractor.stateFromItem(itemNew);

                i++;
            }
        }

        moveFurni(furniId, originalLocation.getX(), originalLocation.getY(), originalRotation, isStackable, -1);
    }


    private long latestConditionSave = -1;
    private long latestTriggerSave = -1;
    private long latestEffectSave = -1;
    private long latestAddonSave = -1;
    private long latestSelectorSave = -1;
    private long latestVariableSave = -1;

    private Semaphore wiredSaveConfirmation = new Semaphore(0);
    private Semaphore wiredVariableConfirmation = new Semaphore(0);

    private void wiredSaved(HMessage hMessage) {
        if (state == BuildingImportState.SETUP_WIRED) {
            wiredSaveConfirmation.release();
        }
    }

    private void saveWired(PresetWiredBase presetWired, int attempt) {
        if (attempt > 2) return;

        long latestSave = 0;

        if (presetWired instanceof PresetWiredCondition) latestSave = latestConditionSave;
        else if (presetWired instanceof PresetWiredEffect) latestSave = latestEffectSave;
        else if (presetWired instanceof PresetWiredTrigger) latestSave = latestTriggerSave;
        else if (presetWired instanceof PresetWiredAddon) latestSave = latestAddonSave;
        else if (presetWired instanceof PresetWiredSelector) latestSave = latestSelectorSave;
        else if (presetWired instanceof PresetWiredVariable) latestSave = latestVariableSave;

        int delay = 300 - ((int)(Math.min(300, System.currentTimeMillis() - latestSave)));
        if (delay > 0) Utils.sleep(delay);

        wiredSaveConfirmation.drainPermits();
        wiredVariableConfirmation.drainPermits();

        if (presetWired.getVariableIds() != null && presetWired.getVariableIds().size() > 0) {
            needVariableIds.clear();
            needVariableIds.addAll(presetWired.getVariableIds());
            needVariableIds = needVariableIds.stream().filter(id -> id.equals("0") && realVariableIdMap.keySet().stream().noneMatch(x -> x.equals(id))).collect(Collectors.toList());

            if(needVariableIds.size() > 0) {
                extension.sendToServer(new HPacket("WiredGetAllVariablesDiffs", HMessage.Direction.TOSERVER, 0));
                boolean gotVariableConfirmation = false;
                try { gotVariableConfirmation = wiredVariableConfirmation.tryAcquire(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {}
            }
        }

        PresetWiredBase presetWiredBase;
        synchronized (lock) {
            presetWiredBase = presetWired.applyWiredConfig(extension, realFurniIdMap, realVariableIdMap);
        }

        if (presetWired instanceof PresetWiredCondition) latestConditionSave = System.currentTimeMillis();
        else if (presetWired instanceof PresetWiredEffect) latestEffectSave = System.currentTimeMillis();
        else if (presetWired instanceof PresetWiredTrigger) latestTriggerSave = System.currentTimeMillis();
        else if (presetWired instanceof PresetWiredAddon) latestAddonSave = System.currentTimeMillis();
        else if (presetWired instanceof PresetWiredSelector) latestSelectorSave = System.currentTimeMillis();
        else if (presetWired instanceof PresetWiredVariable) latestVariableSave = System.currentTimeMillis();

        boolean gotConfirmation = false;
        try { gotConfirmation = wiredSaveConfirmation.tryAcquire(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {}

        if (presetWired instanceof PresetWiredVariable) {
            variablesProcessed++;
            
            if(variablesProcessed >= variablesToProcess) {
                extension.sendToServer(new HPacket("WiredGetAllVariablesDiffs", HMessage.Direction.TOSERVER, 0));
                boolean gotVariableConfirmation = false;
                try { gotVariableConfirmation = wiredVariableConfirmation.tryAcquire(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {}
            }
        }

        if (gotConfirmation) {
            if (presetWiredBase != null) {
                extension.getExporter().cacheWiredConfig(presetWiredBase);
            }
        }
        else {
            saveWired(presetWired, attempt + 1);
        }
    }

    private void moveFurniture() {
        FurniDataTools furniData = extension.getFurniDataTools();

        List<PresetFurni> moveList = new ArrayList<>();

        synchronized (lock) {
            workingPresetConfig.getFurniture().forEach(p -> {
                if (furniData.isStackable(p.getClassName()) && !p.getClassName().startsWith("wf_trg_") && !isStackTileClass(p.getClassName()) && realFurniIdMap.containsKey(p.getFurniId())) {
                    moveList.add(p);
                }
            });
            workingPresetConfig.getFurniture().forEach(p -> {
                if (furniData.isStackable(p.getClassName()) && p.getClassName().startsWith("wf_trg_") && realFurniIdMap.containsKey(p.getFurniId())) {
                    moveList.add(p);
                }
            });
        }

        Map<Integer, PresetFurni> movesByRealId = new HashMap<>();

        int i = 0;
        while (state == BuildingImportState.MOVE_FURNITURE && i < moveList.size()) {
            PresetFurni moveFurni = moveList.get(i);
            i++;
            int realFurniId = realFurniIdMap.get(moveFurni.getFurniId());

            if (moveFurni.getState() != null) {
                attemptSetState(realFurniId, moveFurni.getState());
            }

            movesByRealId.put(realFurniId, moveFurni);
            moveFurni(
                    realFurniId,
                    moveFurni.getLocation().getX() + rootLocation.getX(),
                    moveFurni.getLocation().getY() + rootLocation.getY(),
                    moveFurni.getRotation(),
                    true,
                    moveFurni.getLocation().getZ()// + rootLocation.getZ()
            );
        }


        // second pass
        Utils.sleep(300 + extension.getSafeFeedbackTimeout());
        if (state == BuildingImportState.MOVE_FURNITURE) {
            List<HFloorItem> maybeNotMovedItems = extension.getFloorState().getFurniOnTile(stackTileLocation.getX(), stackTileLocation.getY());
            maybeNotMovedItems.addAll(extension.getFloorState().getFurniOnTile(stackTileLocation.getX(), stackTileLocation.getY()));
            maybeNotMovedItems.addAll(extension.getFloorState().getFurniOnTile(stackTileLocation.getX(), stackTileLocation.getY()));
            maybeNotMovedItems = maybeNotMovedItems.stream().filter(h -> {
                PresetFurni p = movesByRealId.get(h.getId());
                if (p == null) return false;
                return p.getLocation().getX() + rootLocation.getX() != h.getTile().getX() ||
                        p.getLocation().getY() + rootLocation.getY() != h.getTile().getY() ||
                        p.getRotation() != h.getFacing().ordinal();
            }).collect(Collectors.toList());

            for (HFloorItem o : maybeNotMovedItems) {
                if (state != BuildingImportState.MOVE_FURNITURE) break;

                PresetFurni p = movesByRealId.get(o.getId());

                moveFurni(
                        o.getId(),
                        p.getLocation().getX() + rootLocation.getX(),
                        p.getLocation().getY() + rootLocation.getY(),
                        p.getRotation(),
                        true,
                        p.getLocation().getZ()// + rootLocation.getZ()
                );
            }
        }

        if (state == BuildingImportState.MOVE_FURNITURE) {
            for(StackTileInfo stackTileInfo : allAvailableStackTiles) {
                moveFurni(stackTileInfo.getFurniId(), stackTileInfo.getLocation().getX(), stackTileInfo.getLocation().getY(),
                        stackTileInfo.getRotation(), false, -1);
            }

        }

        // Place wall items
        if (state == BuildingImportState.MOVE_FURNITURE) {
            placeWallItems();
        }

        // Place preset stack tiles last
        if (state == BuildingImportState.MOVE_FURNITURE) {
            placePresetStackTiles();
        }

        synchronized (lock) {
            if (state == BuildingImportState.MOVE_FURNITURE) {
                state = BuildingImportState.NONE;
                extension.sendVisualChatInfo("Imported the preset successfully");
                extension.getLogger().log("Finished importing the preset!", "green");
            }
        }
    }

    private void placeWallItems() {
        List<PresetWallFurni> wallFurniture = workingPresetConfig.getWallFurniture();
        if (wallFurniture == null || wallFurniture.isEmpty()) return;

        extension.sendVisualChatInfo("Placing wall items..");
        FurniDataTools furniData = extension.getFurniDataTools();
        Inventory inventory = extension.getInventory();
        BCCatalog catalog = extension.getCatalog();
        ItemSource source = postConfig.getItemSource();
        FloorState floor = extension.getFloorState();

        List<int[]> wallItemStateQueue = new ArrayList<>();

        for (PresetWallFurni wallFurni : wallFurniture) {
            if (state != BuildingImportState.MOVE_FURNITURE) break;

            String className = wallFurni.getClassName();
            String wallState = wallFurni.getState() != null ? wallFurni.getState() : "";
            Integer typeId = furniData.getWallTypeId(className);
            if (typeId == null) {
                extension.sendVisualChatInfo(String.format("WARNING: Unknown wall item '%s', skipping", className));
                continue;
            }

            WallPosition relPos = wallFurni.getLocation();
            int absX = relPos.getX() + rootLocation.getX();
            int absY = relPos.getY() + rootLocation.getY();
            int absAltitude = relPos.getAltitude() + heightOffset * 100;

            WallPosition absPos = new WallPosition(
                    absX, absY,
                    relPos.getOffsetX(), relPos.getOffsetY(),
                    relPos.getDirection(), absAltitude
            );

            String locationString = absPos.toString();

            boolean isPoster = "poster".equals(className);

            String invCacheKey = isPoster ? typeId + ":" + wallState : String.valueOf(typeId);
            LinkedList<HInventoryItem> inventoryItems = wallInventoryCache.computeIfAbsent(
                    invCacheKey, k -> {
                        List<HInventoryItem> allOfType = inventory.getWallItemsByType(typeId);
                        if (isPoster && !wallState.isEmpty()) {
                            return allOfType.stream()
                                    .filter(item -> {
                                        try {
                                            String itemState = item.getStuff() != null ? item.getStuff().getLegacyString() : "";
                                            return wallState.equals(itemState);
                                        } catch (Exception e) {
                                            return false;
                                        }
                                    })
                                    .collect(Collectors.toCollection(LinkedList::new));
                        }
                        return new LinkedList<>(allOfType);
                    }
            );

            BCCatalog.SingleFurniProduct bcProduct = null;
            if (catalog != null && catalog.getState() == BCCatalog.CatalogState.COLLECTED) {
                bcProduct = catalog.getWallProduct(typeId, wallState);
            }

            int offerId = bcProduct != null ? bcProduct.getOfferId() : -1;
            int pageId = bcProduct != null ? bcProduct.getPageId() : -1;
            if (offerId == -1 && furniData.getWallItemDetails(className) != null) {
                offerId = furniData.getWallItemDetails(className).offerId;
                pageId = -1;
            }

            boolean useBC;
            if (source == ItemSource.ONLY_BC) {
                useBC = true;
            } else if (source == ItemSource.PREFER_BC) {
                if (bcProduct != null) {
                    useBC = true;
                } else if (!inventoryItems.isEmpty()) {
                    useBC = false;
                } else {
                    useBC = offerId != -1;
                }
            } else if (source == ItemSource.PREFER_INVENTORY) {
                useBC = inventoryItems.isEmpty() && offerId != -1;
            } else {
                useBC = false;
            }

            if (useBC) {
                if (offerId == -1) {
                    if (!extension.allowIncompleteBuilds()) {
                        state = BuildingImportState.NONE;
                        extension.sendVisualChatInfo(String.format("ERROR: Couldn't find wall item '%s' in BC catalog.. aborting", className));
                    } else {
                        extension.sendVisualChatInfo(String.format("Couldn't find wall item '%s' in BC catalog.. continuing", className));
                    }
                    continue;
                }
                Set<Integer> wallIdsBefore = new HashSet<>();
                if (!isPoster && !wallState.isEmpty()) {
                    for (HWallItem wi : floor.getWallItems()) wallIdsBefore.add(wi.getId());
                }

                extension.sendToServer(new HPacket(
                        "BuildersClubPlaceWallItem",
                        HMessage.Direction.TOSERVER,
                        pageId,
                        offerId,
                        wallState,
                        locationString,
                        false
                ));
                Utils.sleep(230);

                if (!isPoster && !wallState.isEmpty()) {
                    for (HWallItem wi : floor.getWallItems()) {
                        if (!wallIdsBefore.contains(wi.getId())) {
                            if (!wallState.equals(wi.getState())) {
                                try {
                                    int targetStateInt = Integer.parseInt(wallState);
                                    wallItemStateQueue.add(new int[]{wi.getId(), targetStateInt});
                                } catch (NumberFormatException ignored) {}
                            }
                            break;
                        }
                    }
                }
            } else {
                if (inventoryItems.isEmpty()) {
                    if (!extension.allowIncompleteBuilds()) {
                        extension.sendVisualChatInfo(String.format("ERROR: Couldn't find wall item '%s' (state=%s) in inventory.. aborting", className, wallState));
                        state = BuildingImportState.NONE;
                        return;
                    }
                    extension.sendVisualChatInfo(String.format("Couldn't find wall item '%s' (state=%s) in inventory.. continuing", className, wallState));
                    continue;
                }

                HInventoryItem item = inventoryItems.pollFirst();

                Set<Integer> wallIdsBefore = new HashSet<>();
                if (!isPoster && !wallState.isEmpty()) {
                    for (HWallItem wi : floor.getWallItems()) wallIdsBefore.add(wi.getId());
                }

                String placeCmd = item.getId() + " " + locationString;

                extension.sendToServer(new HPacket(
                        "PlaceObject",
                        HMessage.Direction.TOSERVER,
                        placeCmd
                ));

                Utils.sleep(Math.max(extension.getSafeFeedbackTimeout(), 100));

                if (!isPoster && !wallState.isEmpty()) {
                    for (HWallItem wi : floor.getWallItems()) {
                        if (!wallIdsBefore.contains(wi.getId())) {
                            if (!wallState.equals(wi.getState())) {
                                try {
                                    int targetStateInt = Integer.parseInt(wallState);
                                    wallItemStateQueue.add(new int[]{wi.getId(), targetStateInt});
                                } catch (NumberFormatException ignored) {}
                            }
                            break;
                        }
                    }
                }
            }
        }

        if (!wallItemStateQueue.isEmpty()) {
            extension.sendVisualChatInfo("Setting wall item states..");
            for (int[] entry : wallItemStateQueue) {
                if (state != BuildingImportState.MOVE_FURNITURE) break;
                int wallItemId = entry[0];
                int targetState = entry[1];

                HWallItem wallItem = floor.wallItemFromId(wallItemId);
                if (wallItem == null) continue;

                String currentState = wallItem.getState();
                int attempts = 0;
                while (state != BuildingImportState.NONE && !String.valueOf(targetState).equals(currentState) && attempts < 20) {
                    extension.sendToServer(new HPacket("UseWallItem", HMessage.Direction.TOSERVER, wallItemId, 0));
                    Utils.sleep(Math.max(extension.getSafeFeedbackTimeout(), 100));

                    wallItem = floor.wallItemFromId(wallItemId);
                    currentState = wallItem != null ? wallItem.getState() : currentState;
                    attempts++;
                }
            }
        }
    }

    private void placePresetStackTiles() {
        FurniDataTools furniData = extension.getFurniDataTools();
        FloorState floor = extension.getFloorState();
        List<PresetFurni> presetStackTiles = new ArrayList<>();

        synchronized (lock) {
            for (PresetFurni f : workingPresetConfig.getFurniture()) {
                if (isStackTileClass(f.getClassName()) && furniData.isStackable(f.getClassName())) {
                    presetStackTiles.add(f);
                }
            }
        }

        if (presetStackTiles.isEmpty()) return;

        extension.sendVisualChatInfo(String.format("Placing %d stack tile(s)...", presetStackTiles.size()));

        for (PresetFurni stackTileFurni : presetStackTiles) {
            if (state != BuildingImportState.MOVE_FURNITURE) break;

            int targetX = stackTileFurni.getLocation().getX() + rootLocation.getX();
            int targetY = stackTileFurni.getLocation().getY() + rootLocation.getY();
            int targetRot = stackTileFurni.getRotation();

            // Track items before drop to identify the new one
            Set<Integer> idsBefore = new HashSet<>();
            for (HFloorItem fi : floor.getItems()) idsBefore.add(fi.getId());

            // Drop at the helper stack tile location
            FurniDropInfo dropInfo = new FurniDropInfo(
                    stackTileLocation.getX(),
                    stackTileLocation.getY(),
                    furniData.getFloorTypeId(stackTileFurni.getClassName()),
                    postConfig.getItemSource(),
                    0
            );
            dropFurni(dropInfo);
            Utils.sleep(300);

            // Find the newly placed item
            HFloorItem newItem = null;
            for (HFloorItem fi : floor.getItems()) {
                if (!idsBefore.contains(fi.getId())) {
                    newItem = fi;
                    break;
                }
            }

            if (newItem != null) {
                int realId = newItem.getId();
                realFurniIdMap.put(stackTileFurni.getFurniId(), realId);

                // Move to correct position
                moveFurni(realId, targetX, targetY, targetRot, false, -1);

                // Set custom stacking height from the Z coordinate
                double z = stackTileFurni.getLocation().getZ();
                int heightCenti = ((int) (z * 100)) + heightOffset * 100;
                extension.sendToServer(new HPacket(
                        "SetCustomStackingHeight",
                        HMessage.Direction.TOSERVER,
                        realId, heightCenti
                ));
                Utils.sleep(60);

                // Set state if available
                if (stackTileFurni.getState() != null) {
                    attemptSetState(realId, stackTileFurni.getState());
                }
            }
        }
    }

    private void setupWired() {
        List<PresetWiredBase> allWireds = new ArrayList<>();
        Map<Integer, List<PresetWiredFurniBinding>> wiredBindings = new HashMap<>();
        Map<Integer, PresetFurni> furni = new HashMap<>();

        synchronized (lock) {
            PresetWireds presetWireds = workingPresetConfig.getPresetWireds();

            int maxSize = Math.max(presetWireds.getConditions().size(),
                    Math.max(presetWireds.getEffects().size(),
                            Math.max(presetWireds.getTriggers().size(),
                                    Math.max(presetWireds.getAddons().size(),
                                            Math.max(presetWireds.getSelectors().size(),
                                                presetWireds.getVariables().size()
                                            )
                                    )
                            )
                    )
            );

            Predicate<PresetWiredBase> filter = (wiredBase) -> realFurniIdMap.containsKey(wiredBase.getWiredId());

            List<PresetWiredVariable> variables = presetWireds.getVariables().stream().filter(filter).collect(Collectors.toList());
            allWireds.addAll(variables);
            allWireds.addAll(presetWireds.getConditions().stream().filter(filter).collect(Collectors.toList()));
            allWireds.addAll(presetWireds.getEffects().stream().filter(filter).collect(Collectors.toList()));
            allWireds.addAll(presetWireds.getTriggers().stream().filter(filter).collect(Collectors.toList()));
            allWireds.addAll(presetWireds.getAddons().stream().filter(filter).collect(Collectors.toList()));
            allWireds.addAll(presetWireds.getSelectors().stream().filter(filter).collect(Collectors.toList()));
            allWireds.addAll(presetWireds.getAddons().stream().filter(filter).collect(Collectors.toList()));

            workingPresetConfig.getBindings().forEach(b -> {
                if (!wiredBindings.containsKey(b.getWiredId())) wiredBindings.put(b.getWiredId(), new ArrayList<>());
                wiredBindings.get(b.getWiredId()).add(b);
            });
            workingPresetConfig.getFurniture().forEach(p -> furni.put(p.getFurniId(), p));
            
            variablesProcessed = 0;
            variablesToProcess = variables.size();
        }
        
        int i = 0;
        while (state == BuildingImportState.SETUP_WIRED && i < allWireds.size()) {
            PresetWiredBase wiredBase = allWireds.get(i);

            if ((i + 1) % 50 == 0) {
                extension.getLogger().log(String.format("Setting up wired.. (%d/%d)", i+1, allWireds.size()), "orange");
            }

            i++;

            FloorState floor = extension.getFloorState();
            FurniDataTools furniData = extension.getFurniDataTools();

            List<PresetWiredFurniBinding> bindings = wiredBindings.get(wiredBase.getWiredId());

            List<FurniMoveInfo> bindMoves = new ArrayList<>();
            List<FurniMoveInfo> undoBindMoves = new ArrayList<>();

            if (bindings != null) {
                // set stuff to the right rotation/location/state
                for (PresetWiredFurniBinding binding : bindings) {
                    int furniId;
                    synchronized (lock) {
                        if (state != BuildingImportState.SETUP_WIRED) return;

                        if (!realFurniIdMap.containsKey(binding.getFurniId())) continue;
                        furniId = realFurniIdMap.get(binding.getFurniId());
                    }
                    if (binding.getState() != null) {
                        String targetState = binding.getState();
                        attemptSetState(furniId, targetState);
                    }
                    synchronized (lock) {
                        if (state != BuildingImportState.SETUP_WIRED) return;

                        boolean needMovement = binding.getLocation() != null || binding.getRotation() != null || binding.getAltitude() != null;
                        if (needMovement) {
                            HFloorItem floorItem = floor.furniFromId(furniId);
                            if (floorItem != null) {
                                boolean needStacktile =
                                        furniData.isStackable(furniData.getFloorItemName(extension.getFloorState().furniFromId(furniId).getTypeId()))
                                        || binding.getAltitude() != null;

                                FurniMoveInfo undoMovement = new FurniMoveInfo(
                                        furniId, floorItem.getTile().getX(), floorItem.getTile().getY(),
                                        floorItem.getFacing().ordinal(), null, needStacktile);
                                undoBindMoves.add(undoMovement);

                                int targetX = floorItem.getTile().getX() + rootLocation.getX();
                                int targetY = floorItem.getTile().getY() + rootLocation.getY();
                                int targetRotation = floorItem.getFacing().ordinal();
                                Integer targetAltitude = null;

                                if (binding.getLocation() != null) {
                                    targetX = binding.getLocation().getX() + rootLocation.getX();
                                    targetY = binding.getLocation().getY() + rootLocation.getY();
                                }
                                if (binding.getRotation() != null) {
                                    targetRotation = binding.getRotation();
                                }
                                if (binding.getAltitude() != null) {
                                    targetAltitude = binding.getAltitude();
                                }

                                FurniMoveInfo movement = new FurniMoveInfo(
                                        furniId, targetX, targetY, targetRotation, targetAltitude, needStacktile);
                                bindMoves.add(movement);
                            }
                        }
                    }


                }
            }

            for (FurniMoveInfo furniMoveInfo : bindMoves) {
                double altitude = furniMoveInfo.getAltitude() == null ? -1 : ((double) furniMoveInfo.getAltitude()) / 100;
                moveFurni(
                        furniMoveInfo.getFurniId(), furniMoveInfo.getX(), furniMoveInfo.getY(),
                        furniMoveInfo.getRotation(), furniMoveInfo.useStackTile(), altitude
                );
            }

            saveWired(wiredBase, 0);

            for (FurniMoveInfo furniMoveInfo : undoBindMoves) {
                double altitude = furniMoveInfo.getAltitude() == null ? -1 : ((double) furniMoveInfo.getAltitude()) / 100;
                moveFurni(
                        furniMoveInfo.getFurniId(), furniMoveInfo.getX(), furniMoveInfo.getY(),
                        furniMoveInfo.getRotation(), furniMoveInfo.useStackTile(), altitude
                );
                // todo maybe set to original height? perhaps needed when furni is overridden with existing furni
            }
        }


        synchronized (lock) {
            if (state == BuildingImportState.SETUP_WIRED) {
                state = BuildingImportState.MOVE_FURNITURE;
            }
        }
        if (state == BuildingImportState.MOVE_FURNITURE) {
            extension.sendVisualChatInfo("Setting furni in their correct position..");
            moveFurniture();
        }
    }

    private void dropAllOtherFurni() {
        FurniDataTools furniData = extension.getFurniDataTools();
        List<FurniDropInfo> furniDropInfos = new ArrayList<>();

        moveFurni(mainStackTile, stackTileLocation.getX(), stackTileLocation.getY(), 0, false, -1);

        synchronized (lock) {
            workingPresetConfig.getFurniture().forEach(f -> {
                if (furniData.isStackable(f.getClassName()) && !isStackTileClass(f.getClassName())) {
                    FurniDropInfo dropInfo = new FurniDropInfo(
                            f.getClassName().startsWith("wf_trg_") ?
                                    stackTileLocation.getX() + 1 :
                                    stackTileLocation.getX(),
                            stackTileLocation.getY(),
                            furniData.getFloorTypeId(f.getClassName()),
                            postConfig.getItemSource(),
                            0);
                    furniDropInfos.add(dropInfo);

                    String key = String.format("%d|%d|%d", dropInfo.getX(), dropInfo.getY(), dropInfo.getTypeId());
                    if (!expectFurniDrops.containsKey(key))
                        expectFurniDrops.put(key, new LinkedList<>());
                    expectFurniDrops.get(key).add(f.getFurniId());
                }
            });
        }

        int i = 0;
        while (i < furniDropInfos.size() && state == BuildingImportState.ADD_FURNITURE) {
            FurniDropInfo dropInfo = furniDropInfos.get(i);

            if ((i + 1) % 50 == 0) {
                extension.getLogger().log(String.format("Dropping furni.. (%d/%d)", i+1, furniDropInfos.size()), "orange");
            }

            dropFurni(dropInfo);
            i++;
        }

        if (state == BuildingImportState.ADD_FURNITURE) {
            int j = 0;
            boolean done;
            do {
                Utils.sleep(500);
                synchronized (lock) {
                    done = state != BuildingImportState.ADD_FURNITURE || expectFurniDrops.isEmpty() || j++ > 7;
                }
            } while (!done);

            synchronized (lock) {
                if (state == BuildingImportState.ADD_FURNITURE) {
                    if (expectFurniDrops.isEmpty() || extension.allowIncompleteBuilds()) {
                        state = BuildingImportState.SETUP_ADS;
                    }
                    else {
                        state = BuildingImportState.NONE;
                        extension.sendVisualChatInfo("ERROR: not all furniture were placed");
                        extension.getLogger().log("ERROR: not all furniture were placed", "red");
                    }
                }
            }
            if (state == BuildingImportState.SETUP_ADS) {
                setupAds();
            }
        }
    }

    private void setupAds() {
        List<PresetAdsBackground> adsBackgrounds = workingPresetConfig.getAdsBackgrounds();
        if (!adsBackgrounds.isEmpty()) {
            extension.sendVisualChatInfo("Setting up ads backgrounds..");

            int index = 0;
            while (index < adsBackgrounds.size() && state == BuildingImportState.SETUP_ADS) {
                PresetAdsBackground adsBackground = adsBackgrounds.get(index);

                synchronized (lock) {
                    if (state == BuildingImportState.SETUP_ADS && realFurniIdMap.containsKey(adsBackground.getFurniId())) {
                        int realFurni = realFurniIdMap.get(adsBackground.getFurniId());
                        extension.sendToServer(
                                new HPacket("SetObjectData", HMessage.Direction.TOSERVER,
                                        realFurni, 8,
                                        "imageUrl", adsBackground.getImageUrl(),
                                        "offsetX", adsBackground.getOffsetX(),
                                        "offsetY", adsBackground.getOffsetY(),
                                        "offsetZ", adsBackground.getOffsetZ()
                                )
                        );
                    }
                }

                Utils.sleep(100);
                index++;
            }
        }

        synchronized (lock) {
            if (state == BuildingImportState.SETUP_ADS) {
                state = BuildingImportState.SETUP_WIRED;
            }
        }
        if (state == BuildingImportState.SETUP_WIRED) {
            extension.sendVisualChatInfo("Setting up wired..");
            setupWired();
        }
    }

    private boolean locationContainsWired(int x, int y) {
        FloorState floor = extension.getFloorState();
        FurniDataTools furniData = extension.getFurniDataTools();

        return floor.getFurniOnTile(x, y).stream().anyMatch(i -> {
            String className = furniData.getFloorItemName(i.getTypeId());
            return className.startsWith("wf_cnd_") ||
                    className.startsWith("wf_trg_") ||
                    className.startsWith("wf_act_");
        });
    }

    private HPoint findStackTileLocation() {
        // can not be the unoccupied space
        // can also not be a place with wired in the first or second tile (top row)
        // this will be the place where all furniture is placed on
        FloorState floor = extension.getFloorState();

        // Search near the selected root location / reserved space instead of from (0,0)
        int centerX = rootLocation != null ? rootLocation.getX() : 0;
        int centerY = rootLocation != null ? rootLocation.getY() : 0;

        // Expanding search by distance from center
        int maxDist = 64;
        for (int dist = 0; dist < maxDist; dist++) {
            for (int dx = -dist; dx <= dist; dx++) {
                for (int dy = -dist; dy <= dist; dy++) {
                    if (Math.abs(dx) != dist && Math.abs(dy) != dist) continue; // only check the perimeter of this distance ring

                    int x = centerX + dx;
                    int y = centerY + dy;

                    if (x < 0 || y < 0 || x >= 64 - mainStackDimensions || y >= 64 - mainStackDimensions) continue;

                    char reference = floor.floorHeight(x, y);
                    if (reference == 'x') continue;

                    boolean valid = true;
                    for (int xOffset = 0; xOffset < mainStackDimensions && valid; xOffset++) {
                        for (int yOffset = 0; yOffset < mainStackDimensions && valid; yOffset++) {
                            if (floor.floorHeight(x + xOffset, y + yOffset) != reference) {
                                valid = false;
                            }
                        }
                    }

                    if (valid && !locationContainsWired(x, y) && !locationContainsWired(x + 1, y)) {
                        return new HPoint(x, y);
                    }
                }
            }
        }
        return new HPoint(centerX, centerY);
    }

    private void addUnstackables() {
        FurniDataTools furniData = extension.getFurniDataTools();
        List<FurniDropInfo> furniDropInfos = new ArrayList<>();

        synchronized (lock) {
            workingPresetConfig.getFurniture().forEach(f -> {
                if (!furniData.isStackable(f.getClassName())) {
                    FurniDropInfo dropInfo = new FurniDropInfo(
                            f.getLocation().getX() + rootLocation.getX(),
                            f.getLocation().getY() + rootLocation.getY(),
                            furniData.getFloorTypeId(f.getClassName()),
                            postConfig.getItemSource(),
                            f.getRotation());

                    furniDropInfos.add(dropInfo);

                    String key = String.format("%d|%d|%d", dropInfo.getX(), dropInfo.getY(), dropInfo.getTypeId());
                    if (!expectFurniDrops.containsKey(key))
                        expectFurniDrops.put(key, new LinkedList<>());
                    expectFurniDrops.get(key).add(f.getFurniId());
                }
            });
        }

        int i = 0;
        while (i < furniDropInfos.size() && state == BuildingImportState.ADD_UNSTACKABLES) {
            FurniDropInfo dropInfo = furniDropInfos.get(i);
            dropFurni(dropInfo);

            i++;
        }
//
//        if (state == WiredImportState.ADD_UNSTACKABLES) {
//            if (furniDropInfos.size() > 0) Utils.sleep(2500);
            synchronized (lock) {
                if (state == BuildingImportState.ADD_UNSTACKABLES) {
                    state = BuildingImportState.ADD_FURNITURE;
                }
            }
//        }

        if (state == BuildingImportState.ADD_FURNITURE) {
            dropAllOtherFurni();
        }

    }

    public void reset() {
        useRoomFurniRectCorner1 = null;
        useRoomFurniRectCorner2 = null;
        stateChangerLocation = null;
        state = BuildingImportState.NONE;
    }

    public void setPresetConfig(PresetConfig presetConfig) {
        this.presetConfig = presetConfig;
    }

    public PresetConfig getPresetConfig() {
        return presetConfig;
    }

    public void setPostConfig(PostConfig postConfig) {
        this.postConfig = postConfig;
    }

//    public PostConfig getPostConfig() {
//        return postConfig;
//    }

    public BuildingImportState getState() {
        return state;
    }
}
