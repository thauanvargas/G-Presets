package extension.tools;

import extension.GPresets;
import extension.parsers.HWiredVariablesForObject;
import extension.parsers.HWiredVariable;
import extension.parsers.VariableInternalType;
import extension.tools.presetconfig.PresetConfig;
import extension.tools.presetconfig.PresetConfigUtils;
import extension.tools.presetconfig.ads_bg.PresetAdsBackground;
import extension.tools.presetconfig.binding.PresetWiredFurniBinding;
import extension.tools.presetconfig.furni.PresetFurni;
import extension.tools.presetconfig.furni.PresetWallFurni;
import extension.tools.presetconfig.wired.*;
import extension.tools.presetconfig.wired.incoming.*;
import furnidata.FurniDataTools;
import game.FloorState;
import gearth.extensions.parsers.HFloorItem;
import gearth.extensions.parsers.HPoint;
import gearth.extensions.parsers.HWallItem;
import gearth.extensions.parsers.stuffdata.MapStuffData;
import gearth.protocol.HMessage;
import gearth.protocol.HPacket;
import utils.StateExtractor;
import utils.Utils;
import utils.WallPosition;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class GPresetExporter {

    private final Object lock = new Object();
    private HashMap<String, String> variablesMap = new HashMap<>();
    private HashMap<String, String> variableIdToName = new HashMap<>();
    private final Map<Integer, Map<String, Integer>> furniVariablesByFurniId = Collections.synchronizedMap(new HashMap<>());
    private final Set<Integer> pendingFurniVariableRequests = Collections.synchronizedSet(new HashSet<>());
    private final Set<String> pendingVariableHolderRequests = Collections.synchronizedSet(new HashSet<>());
    private final Set<Integer> exportAreaFurniIds = Collections.synchronizedSet(new HashSet<>());
    private volatile long lastVariableActivityTime = 0L;
    private volatile int totalVariableRequests = 0;
    private volatile int sentVariableRequests = 0;
    private volatile boolean variableRequestPhaseStarted = false;
    private boolean hasVariableMap = false;

    private static final long VARIABLE_GRACE_WINDOW_MS = 3000L;
    private static final long VARIABLE_REQUEST_INTERVAL_MS = 250L;
    private static final int VARIABLE_MAX_RETRIES = 2;

    public enum PresetExportState {
        NONE,
        AWAITING_RECT1,
        AWAITING_RECT2,
        AWAITING_NAME,
        FETCHING_UNKNOWN_CONFIGS
    }

    private volatile HPoint rectCorner1 = null;
    private volatile HPoint rectCorner2 = null;
    private volatile String exportName = null;
    private volatile boolean wallOnlyExport = false;

    private volatile PresetExportState state = PresetExportState.NONE;

    private final Map<String, PresetWiredCondition> wiredConditionConfigs = Collections.synchronizedMap(new HashMap<>());
    private final Map<String, PresetWiredEffect> wiredEffectConfigs = Collections.synchronizedMap(new HashMap<>());
    private final Map<String, PresetWiredTrigger> wiredTriggerConfigs = Collections.synchronizedMap(new HashMap<>());
    private final Map<String, PresetWiredAddon> wiredAddonConfigs = Collections.synchronizedMap(new HashMap<>());
    private final Map<String, PresetWiredSelector> wiredSelectorConfigs = Collections.synchronizedMap(new HashMap<>());
    private final Map<String, PresetWiredVariable> wiredVariableConfigs = Collections.synchronizedMap(new HashMap<>());

    private static final Set<String> requireBindings = new HashSet<>(Arrays.asList(
            "wf_act_match_to_sshot", "wf_cnd_match_snapshot", "wf_cnd_not_match_snap", "wf_trg_stuff_state"));
    private final Map<String, List<PresetWiredFurniBinding>> wiredFurniBindings = Collections.synchronizedMap(new HashMap<>());


    private final GPresets extension;

    public GPresetExporter(GPresets extension) {
        this.extension = extension;

        extension.intercept(HMessage.Direction.TOSERVER, "UpdateCondition", this::saveCondition);
        extension.intercept(HMessage.Direction.TOSERVER, "UpdateTrigger", this::saveTrigger);
        extension.intercept(HMessage.Direction.TOSERVER, "UpdateAddon", this::saveAddon);
        extension.intercept(HMessage.Direction.TOSERVER, "UpdateSelector", this::saveSelector);
        extension.intercept(HMessage.Direction.TOSERVER, "UpdateAction", this::saveEffect);
        extension.intercept(HMessage.Direction.TOSERVER, "UpdateVariable", this::saveVariable);

        extension.intercept(HMessage.Direction.TOSERVER, "Chat", this::onChat);
        extension.intercept(HMessage.Direction.TOSERVER, "MoveAvatar", this::moveAvatar);

        extension.intercept(HMessage.Direction.TOSERVER, "Open", this::openWired);
        extension.intercept(HMessage.Direction.TOCLIENT, "WiredFurniTrigger", this::retrieveTriggerConf);
        extension.intercept(HMessage.Direction.TOCLIENT, "WiredFurniCondition", this::retrieveConditionConf);
        extension.intercept(HMessage.Direction.TOCLIENT, "WiredFurniAction", this::retrieveActionConf);
        extension.intercept(HMessage.Direction.TOCLIENT, "WiredFurniAddon", this::retrieveAddonConf);
        extension.intercept(HMessage.Direction.TOCLIENT, "WiredFurniSelector", this::retrieveSelectorConf);
        extension.intercept(HMessage.Direction.TOCLIENT, "WiredFurniVariable", this::retrieveVariableConf);
        extension.intercept(HMessage.Direction.TOCLIENT, "WiredAllVariablesDiffs", this::onWiredAllVariables);
        extension.intercept(HMessage.Direction.TOCLIENT, "WiredVariablesForObject", this::onWiredVariablesForObject);
        extension.intercept(HMessage.Direction.TOCLIENT, "WiredAllVariableHolders", this::onWiredAllVariableHolders);

        extension.intercept(HMessage.Direction.TOCLIENT, "ObjectRemove", this::onFurniRemoved);
        extension.intercept(HMessage.Direction.TOCLIENT, "RoomReady", this::onRoomReady);
    }

    private void onRoomReady(HMessage hMessage) {
        hasVariableMap = false;
        variablesMap = new HashMap<>();
        variableIdToName = new HashMap<>();
        furniVariablesByFurniId.clear();
        pendingFurniVariableRequests.clear();
        pendingVariableHolderRequests.clear();
        exportAreaFurniIds.clear();
        variableRequestPhaseStarted = false;
        lastVariableActivityTime = 0L;

        // Per-room wired config caches: clear so that hasWiredVariables() and
        // unRegisteredWiredsInArea() never carry over data from the previous room.
        // Although the cache keys include the room id, hasWiredVariables() iterates
        // every cached entry across all rooms, which can wrongly trigger the
        // "fetch additional 0 wired configurations" branch in attemptExport.
        wiredConditionConfigs.clear();
        wiredEffectConfigs.clear();
        wiredTriggerConfigs.clear();
        wiredAddonConfigs.clear();
        wiredSelectorConfigs.clear();
        wiredVariableConfigs.clear();
        wiredFurniBindings.clear();

        // If the user changes room mid-export, abort cleanly so the next :ep works.
        if (state != PresetExportState.NONE) {
            extension.getLogger().log("Room changed during export, resetting export state", "orange");
            reset();
        }
    }

    private void onWiredVariablesForObject(HMessage hMessage) {
        if (state != PresetExportState.FETCHING_UNKNOWN_CONFIGS) return;

        HWiredVariablesForObject inspection = new HWiredVariablesForObject(hMessage.getPacket());
        if (inspection.type != HWiredVariablesForObject.TYPE_OBJECT || inspection.objectId <= 0) return;

        furniVariablesByFurniId.put(inspection.objectId, new HashMap<>(inspection.variables));
        pendingFurniVariableRequests.remove(inspection.objectId);
        lastVariableActivityTime = System.currentTimeMillis();
        maybeFinishExportAfterRetrieve();
    }

    private void onWiredAllVariableHolders(HMessage hMessage) {
        if (state != PresetExportState.FETCHING_UNKNOWN_CONFIGS) return;

        try {
            HPacket packet = hMessage.getPacket();
            packet.readInteger();

            String variableId = packet.readString();
            packet.readInteger();
            String variableName = packet.readString();
            packet.readInteger();
            packet.readInteger();
            for (int i = 0; i < 8; i++) packet.readBoolean();
            if (packet.readBoolean()) {
                int tcCount = packet.readInteger();
                for (int i = 0; i < tcCount; i++) {
                    packet.readInteger();
                    packet.readString();
                }
            }

            int holdersCount = packet.readInteger();
            int matched = 0;
            for (int i = 0; i < holdersCount; i++) {
                int objectId = packet.readInteger();
                int value = packet.readInteger();
                if (exportAreaFurniIds.contains(objectId)) {
                    furniVariablesByFurniId
                            .computeIfAbsent(objectId, k -> new HashMap<>())
                            .put(variableId, value);
                    matched++;
                }
            }

            pendingVariableHolderRequests.remove(variableId);
            lastVariableActivityTime = System.currentTimeMillis();

            if (matched > 0) {
                extension.getLogger().log(String.format(
                        "Found %d furni holding variable '%s'", matched, variableName), "gray");
            }

            maybeFinishExportAfterRetrieve();
        } catch (Exception e) {
            extension.getLogger().log("Failed to parse WiredAllVariableHolders: " + e.getMessage(), "red");
        }
    }

    private void onWiredAllVariables(HMessage hMessage) {
        if (state == PresetExportState.FETCHING_UNKNOWN_CONFIGS) {
            HPacket packet = hMessage.getPacket();
            int _allVariablesHash = packet.readInteger();
            boolean isLastChunk = packet.readBoolean();

            int removedVariablesLength = packet.readInteger();
            for(int i = 0; i < removedVariablesLength; i++) {
                packet.readString();
            }

            HashSet<HWiredVariable> variables = new HashSet<>();
            int count = packet.readInteger();
            for(int i = 0; i < count; i++) {
                int addedOrUpdated = packet.readInteger();
                variables.add(new HWiredVariable(packet));
            }

            variables.forEach(v -> {
                variableIdToName.put(v.id, v.name);
                if(v.variableInternalType == VariableInternalType.USER_CREATED) {
                    variablesMap.put(v.name, v.id);
                }
            });

            if(isLastChunk) {
                hasVariableMap = true;
                maybeFinishExportAfterRetrieve();
            }
        }
    }

    private boolean isReady() {
        return extension.getFloorState().inRoom() && extension.furniDataReady();
    }

    private void onChat(HMessage hMessage) {
        synchronized (lock) {
            String text = hMessage.getPacket().readString(StandardCharsets.UTF_8);
            if (text.equals(":abort") || text.equals(":a")) {
                hMessage.setBlocked(true);
                if (state != PresetExportState.NONE) {
                    reset();
                    extension.sendVisualChatInfo("Aborted preset export");
                }
            } else if(text.equals(":ep") || text.equals(":exportpreset") || text.equals(":ep all") || text.equals(":exportpreset all")) {
                hMessage.setBlocked(true);

                if (state != PresetExportState.NONE) {
                    extension.sendVisualChatInfo("Already exporting preset.. finish up or abort first");
                } else if (!isReady()) {
                    extension.sendVisualChatInfo("Error: no room detected or furnidata not available");
                } else if (text.equals(":ep") || text.equals(":exportpreset")) {
                    wallOnlyExport = false;
                    state = PresetExportState.AWAITING_RECT1;
                    extension.sendVisualChatInfo("Select the start of the rectangle");
                } else {
                    wallOnlyExport = false;
                    rectCorner1 = new HPoint(0,0 );
                    rectCorner2 = new HPoint(100, 100);
                    state = PresetExportState.AWAITING_NAME;
                    extension.sendVisualChatInfo("Enter the name of the preset");
                }
            } else if(text.equals(":epw") || text.equals(":exportpresetwalls")) {
                hMessage.setBlocked(true);

                if (state != PresetExportState.NONE) {
                    extension.sendVisualChatInfo("Already exporting preset.. finish up or abort first");
                } else if (!isReady()) {
                    extension.sendVisualChatInfo("Error: no room detected or furnidata not available");
                } else {
                    wallOnlyExport = true;
                    rectCorner1 = new HPoint(0, 0);
                    rectCorner2 = new HPoint(100, 100);
                    state = PresetExportState.AWAITING_NAME;
                    extension.sendVisualChatInfo("Enter the name of the preset");
                }
            } else if (state == PresetExportState.AWAITING_NAME) {
                hMessage.setBlocked(true);
                if (!text.matches("[^<>:\"\\/\\\\|?*]*")) {
                    extension.sendVisualChatInfo("Invalid characters in name, don't use the following characters: &lt; &gt; : / \\ | ? *");
                    return;
                }
                int x1 = rectCorner1.getX();
                int y1 = rectCorner1.getY();
                int x2 = rectCorner2.getX();
                int y2 = rectCorner2.getY();

                int dimX = Math.abs(x1 - x2) + 1;
                int dimY = Math.abs(y1 - y2) + 1;

                if (x1 > x2) x1 = x2;
                if (y1 > y2) y1 = y2;

                attemptExport(text, x1, y1, dimX, dimY);
            }
        }
    }

    private void moveAvatar(HMessage hMessage) {
        synchronized (lock) {
            HPacket packet = hMessage.getPacket();

            if (state == PresetExportState.AWAITING_RECT1) {
                hMessage.setBlocked(true);
                int x = packet.readInteger();
                int y = packet.readInteger();
                rectCorner1 = new HPoint(x, y);

                state = PresetExportState.AWAITING_RECT2;
                extension.sendVisualChatInfo("Select the end of the rectangle");
            }
            else if(state == PresetExportState.AWAITING_RECT2) {
                hMessage.setBlocked(true);
                int x = packet.readInteger();
                int y = packet.readInteger();
                rectCorner2 = new HPoint(x, y);

                state = PresetExportState.AWAITING_NAME;
                extension.sendVisualChatInfo("Enter the name of the preset");
            }
        }
    }

    private void maybeSaveBindings(PresetWiredBase wiredBase) {
        wiredFurniBindings.remove(wiredCacheKey(wiredBase.getWiredId()));
        FloorState floorState = extension.getFloorState();
        FurniDataTools furniDataTools = extension.getFurniDataTools();

        HFloorItem wiredItem = floorState.furniFromId(wiredBase.getWiredId());
        String className = furniDataTools.getFloorItemName(wiredItem.getTypeId());

        if(requireBindings.contains(className) && wiredBase.getOptions().size() >= 4) {
            boolean bindState = wiredBase.getOptions().get(0) == 1;
            boolean bindDirection = wiredBase.getOptions().get(1) == 1;
            boolean bindPosition = wiredBase.getOptions().get(2) == 1;
            boolean bindAltitude = wiredBase.getOptions().get(3) == 1;

            List<PresetWiredFurniBinding> bindings = new ArrayList<>();
            wiredBase.getItems().forEach(bindItemId -> {
                HFloorItem bindItem = floorState.furniFromId(bindItemId);

                if (bindItem != null) {
                    PresetWiredFurniBinding binding = new PresetWiredFurniBinding(
                            bindItemId,
                            wiredBase.getWiredId(),
                            !bindPosition ? null : new HPoint(bindItem.getTile().getX(), bindItem.getTile().getY()),
                            !bindDirection ? null : bindItem.getFacing().ordinal(),
                            !bindState ? null : StateExtractor.stateFromItem(bindItem),
                            !bindAltitude ? null : (int) (Math.round(bindItem.getTile().getZ() * 100))
                    );
                    bindings.add(binding);
                }
            });

            if (bindings.size() > 0) {
                wiredFurniBindings.put(wiredCacheKey(wiredBase.getWiredId()), bindings);
            }
        }
    }

    private void saveEffect(HMessage hMessage) {
        if (isReady()) {
            PresetWiredEffect preset = new PresetWiredEffect(hMessage.getPacket());
            wiredEffectConfigs.put(wiredCacheKey(preset.getWiredId()), preset);
            maybeSaveBindings(preset);
        }
    }

    private void saveTrigger(HMessage hMessage) {
        if (isReady()) {
            PresetWiredTrigger preset = new PresetWiredTrigger(hMessage.getPacket());
            wiredTriggerConfigs.put(wiredCacheKey(preset.getWiredId()), preset);
            maybeSaveBindings(preset);
        }
    }

    private void saveAddon(HMessage hMessage) {
        if (isReady()) {
            PresetWiredAddon preset = new PresetWiredAddon(hMessage.getPacket());
            wiredAddonConfigs.put(wiredCacheKey(preset.getWiredId()), preset);
            maybeSaveBindings(preset);
        }
    }

    private void saveSelector(HMessage hMessage) {
        if (isReady()) {
            PresetWiredSelector preset = new PresetWiredSelector(hMessage.getPacket());
            wiredSelectorConfigs.put(wiredCacheKey(preset.getWiredId()), preset);
            maybeSaveBindings(preset);
        }
    }

    private void saveCondition(HMessage hMessage) {
        if (isReady()) {
            PresetWiredCondition preset = new PresetWiredCondition(hMessage.getPacket());
            wiredConditionConfigs.put(wiredCacheKey(preset.getWiredId()), preset);
            maybeSaveBindings(preset);
        }
    }

    private void saveVariable(HMessage hMessage) {
        if (isReady()) {
            PresetWiredVariable preset = new PresetWiredVariable(hMessage.getPacket());
            wiredVariableConfigs.put(wiredCacheKey(preset.getWiredId()), preset);
            maybeSaveBindings(preset);
        }
    }

    public void reset() {
        rectCorner1 = null;
        rectCorner2 = null;
        exportName = null;
        wallOnlyExport = false;
        state = PresetExportState.NONE;
        variableRequestPhaseStarted = false;
        lastVariableActivityTime = 0L;
        pendingFurniVariableRequests.clear();
        pendingVariableHolderRequests.clear();
        exportAreaFurniIds.clear();
    }

    public synchronized List<Integer> unRegisteredWiredsInArea(int x, int y, int dimX, int dimY) {
        if (isReady()) {
            FloorState floor = extension.getFloorState();
            FurniDataTools furniDataTools = extension.getFurniDataTools();

            List<Integer> unregisteredWired = new ArrayList<>();
            for (int xi = x; xi < x + dimX; xi++) {
                for (int yi = y; yi < y + dimY; yi++) {
                    floor.getFurniOnTile(xi, yi).forEach(f -> {
                        String classname = furniDataTools.getFloorItemName(f.getTypeId());
                        if (    (classname.startsWith("wf_trg_") && !wiredTriggerConfigs.containsKey(wiredCacheKey(f.getId())))
                                || (classname.startsWith("wf_cnd_") && !wiredConditionConfigs.containsKey(wiredCacheKey(f.getId())))
                                || (classname.startsWith("wf_act_") && !wiredEffectConfigs.containsKey(wiredCacheKey(f.getId())))
                                || (classname.startsWith("wf_xtra_") && !wiredAddonConfigs.containsKey(wiredCacheKey(f.getId())))
                                || (classname.startsWith("wf_slc_") && !wiredSelectorConfigs.containsKey(wiredCacheKey(f.getId())))
                                || (classname.startsWith("wf_var_") && !wiredVariableConfigs.containsKey(wiredCacheKey(f.getId())))) {
                            unregisteredWired.add(f.getId());
                        }
                    });
                }
            }

            return unregisteredWired;
        }
        return null;
    }

    private void export(String name, int x, int y, int dimX, int dimY) {
        if (isReady() && (wallOnlyExport || unRegisteredWiredsInArea(x, y, dimX, dimY).size() == 0 || !extension.shouldExportWired())) {
            FloorState floor = extension.getFloorState();
            FurniDataTools furniDataTools = extension.getFurniDataTools();

            List<PresetFurni> allFurni = new ArrayList<>();
            List<PresetWallFurni> allWallFurni = new ArrayList<>();
            List<PresetWiredCondition> allConditions = new ArrayList<>();
            List<PresetWiredEffect> allEffects = new ArrayList<>();
            List<PresetWiredTrigger> allTriggers = new ArrayList<>();
            List<PresetWiredAddon> allAddons = new ArrayList<>();
            List<PresetWiredSelector> allSelectors = new ArrayList<>();
            List<PresetWiredVariable> allVariables = new ArrayList<>();
            List<List<? extends PresetWiredBase>> wiredLists = Arrays.asList(allConditions, allEffects, allTriggers, allAddons, allSelectors, allVariables);
            List<PresetWiredFurniBinding> allBindings = new ArrayList<>();
            List<PresetAdsBackground> allAdsBackgrounds = new ArrayList<>();



            if (!wallOnlyExport) {
                // first pass: fill all items with copies
                for (int xi = x; xi < x + dimX; xi++) {
                    for (int yi = y; yi < y + dimY; yi++) {
                        List<HFloorItem> items = floor.getFurniOnTile(xi, yi);
                        for (HFloorItem f : items) {
                            String classname = furniDataTools.getFloorItemName(f.getTypeId());

                            PresetFurni presetFurni = new PresetFurni(f.getId(), classname, f.getTile(),
                                    f.getFacing().ordinal(), StateExtractor.stateFromItem(f));

                            Map<String, Integer> exportableVariables = getExportableFurniVariables(f.getId());
                            if (!exportableVariables.isEmpty()) {
                                presetFurni.setVariables(exportableVariables);
                            }

                            allFurni.add(presetFurni);

                            if (classname.equals("ads_background") && f.getStuff() instanceof MapStuffData) {
                                Map<String, String> stuffDataMap = (MapStuffData) f.getStuff();
                                allAdsBackgrounds.add(new PresetAdsBackground(
                                        f.getId(),
                                        stuffDataMap.get("imageUrl"),
                                        stuffDataMap.get("offsetX"),
                                        stuffDataMap.get("offsetY"),
                                        stuffDataMap.get("offsetZ")
                                ));
                            }

                            if (extension.shouldExportWired()) {
                                String key = wiredCacheKey(f.getId());

                                if (classname.startsWith("wf_trg_")) {
                                    allTriggers.add(new PresetWiredTrigger(wiredTriggerConfigs.get(key)));
                                }
                                else if (classname.startsWith("wf_cnd_")) {
                                    allConditions.add(new PresetWiredCondition(wiredConditionConfigs.get(key)));
                                }
                                else if (classname.startsWith("wf_act_")) {
                                    allEffects.add(new PresetWiredEffect(wiredEffectConfigs.get(key)));
                                }
                                else if (classname.startsWith("wf_xtra_")) {
                                    allAddons.add(new PresetWiredAddon(wiredAddonConfigs.get(key)));
                                }
                                else if (classname.startsWith("wf_slc_")) {
                                    allSelectors.add(new PresetWiredSelector(wiredSelectorConfigs.get(key)));
                                }
                                else if (classname.startsWith("wf_var_")) {
                                    PresetWiredVariable presetVariable = new PresetWiredVariable(wiredVariableConfigs.get(key));
                                    if(hasVariableMap && (presetVariable.variableId == null || presetVariable.variableId.equals("0"))) {
                                        Optional<Map.Entry<String, String>> op = variablesMap.entrySet().stream().filter(k -> k.getKey().equals(presetVariable.getStringConfig())).findFirst();
                                        op.ifPresent(stringStringEntry -> presetVariable.variableId = stringStringEntry.getValue());
                                    }
                                    allVariables.add(presetVariable);
                                }

                                if (wiredFurniBindings.containsKey(key)) {
                                    allBindings.addAll(wiredFurniBindings.get(key).stream()
                                            .map(b -> new PresetWiredFurniBinding(b)).collect(Collectors.toList()));
                                }
                            }
                        }
                    }
                }
            }

            if (!extension.shouldExportWallItems() && !wallOnlyExport) {
                // skip wall items
            } else {
                List<HWallItem> wallItems = floor.getWallItems();
                for (HWallItem f : wallItems) {
                    String classname = furniDataTools.getWallItemName(f.getTypeId());

                    PresetWallFurni presetWallFurni = new PresetWallFurni(
                            f.getId(),
                            classname,
                            new WallPosition(f.getLocation()),
                            f.getState()
                    );
                    allWallFurni.add(presetWallFurni);
                }
            }



            // second pass: filter wired furni selections & bindings to only contain items in allFurni or allWallFurni
            Set<Integer> allFurniIds = Stream.concat(
                    allFurni.stream().map(PresetFurni::getFurniId),
                    allWallFurni.stream().map(PresetWallFurni::getFurniId)
            ).collect(Collectors.toSet());

            wiredLists.forEach(l -> l.forEach((Consumer<PresetWiredBase>) w -> {
                w.setItems(w.getItems().stream().filter(allFurniIds::contains).collect(Collectors.toList()));
                w.setSecondItems(w.getSecondItems().stream().filter(allFurniIds::contains).collect(Collectors.toList()));
            }));
            allBindings = allBindings.stream().filter(b -> allFurniIds.contains(b.getFurniId())).collect(Collectors.toList());




            // third pass: normalize furni IDs and subtract offset from locations
            int lowestFloorPoint = PresetUtils.lowestFloorPoint(
                    floor,
                    new HPoint(x, y),
                    new HPoint(x + dimX, y + dimY)
            );
            Map<Integer, Integer> mappedFurniIds = new HashMap<>();
            List<Integer> orderedFurniIds = Stream.concat(
                    allFurni.stream().map(PresetFurni::getFurniId),
                    allWallFurni.stream().map(PresetWallFurni::getFurniId)
            ).collect(Collectors.toList());
            for (int i = 0; i < orderedFurniIds.size(); i++) {
                mappedFurniIds.put(orderedFurniIds.get(i), i + 1);
            }

            allFurni.forEach(presetFurni -> {
                presetFurni.setFurniId(mappedFurniIds.get(presetFurni.getFurniId()));
                HPoint oldLocation = presetFurni.getLocation();
                presetFurni.setLocation(new HPoint(
                        oldLocation.getX() - x,
                        oldLocation.getY() - y,
                        oldLocation.getZ() - lowestFloorPoint
                ));
            });
            allWallFurni.forEach(presetWallFurni -> {
                presetWallFurni.setFurniId(mappedFurniIds.get(presetWallFurni.getFurniId()));
                WallPosition oldLocation = presetWallFurni.getLocation();
                presetWallFurni.setLocation(new WallPosition(
                        oldLocation.getX() - x,
                        oldLocation.getY() - y,
                        oldLocation.getOffsetX(),
                        oldLocation.getOffsetY(),
                        oldLocation.getDirection(),
                        oldLocation.getAltitude()
                ));
            });
            wiredLists.forEach(l -> l.forEach((Consumer<PresetWiredBase>) w -> {
                w.setWiredId(mappedFurniIds.get(w.getWiredId()));
                w.setItems(w.getItems().stream().map(mappedFurniIds::get).collect(Collectors.toList()));
                w.setSecondItems(w.getSecondItems().stream().map(mappedFurniIds::get).collect(Collectors.toList()));
            }));
            allBindings.forEach(b -> {
                b.setFurniId(mappedFurniIds.get(b.getFurniId()));
                b.setWiredId(mappedFurniIds.get(b.getWiredId()));
                HPoint oldLocation = b.getLocation();
                Integer oldAltitude = b.getAltitude();
                if (oldLocation != null) {
                    b.setLocation(new HPoint(
                            oldLocation.getX() - x,
                            oldLocation.getY() - y
                    ));
                }
                if (oldAltitude != null) {
                    b.setAltitude(oldAltitude - lowestFloorPoint * 100);
                }
            });
            allAdsBackgrounds.forEach(a -> {
                a.setFurniId(mappedFurniIds.get(a.getFurniId()));
            });


            // fourth pass: assign names to furniture based on class & remove state information from wired class
            Map<String, Integer> classToCount = new HashMap<>();
            allFurni.forEach(presetFurni -> {
                String className = presetFurni.getClassName();
                if (!classToCount.containsKey(className)) {
                    classToCount.put(className, 0);
                }
                int number = classToCount.get(className);
                classToCount.put(className, number + 1);

                String furniName = String.format("%s[%d]", className, number);
                presetFurni.setFurniName(furniName);

                if (className.startsWith("wf_trg_") || className.startsWith("wf_cnd_")
                        || className.startsWith("wf_act_") || className.startsWith("wf_xtra_")
                        || className.startsWith("wf_slc_")
                        || className.startsWith("wf_var_")) {
                    presetFurni.setState(null);
                }
            });
            allWallFurni.forEach(presetWallFurni -> {
                String className = presetWallFurni.getClassName();
                if (!classToCount.containsKey(className)) {
                    classToCount.put(className, 0);
                }
                int number = classToCount.get(className);
                classToCount.put(className, number + 1);

                String furniName = String.format("%s[%d]", className, number);
                presetWallFurni.setFurniName(furniName);
            });



            PresetWireds presetWireds = new PresetWireds(allConditions, allEffects, allTriggers, allAddons, allSelectors, allVariables, variablesMap);
            PresetConfig presetConfig = new PresetConfig(allFurni, allWallFurni, presetWireds, allBindings, allAdsBackgrounds);

            PresetConfigUtils.savePreset(name, presetConfig);
            extension.updateInstalledPresets();

            int variableTotal = 0;
            int furniWithVars = 0;
            for (PresetFurni pf : allFurni) {
                Map<String, Integer> vars = pf.getVariables();
                if (vars != null && !vars.isEmpty()) {
                    furniWithVars++;
                    variableTotal += vars.size();
                }
            }
            if (variableTotal > 0) {
                extension.getLogger().log(String.format(
                        "Exported %d furni variable%s across %d furni",
                        variableTotal, variableTotal == 1 ? "" : "s", furniWithVars), "green");
            }
            extension.sendVisualChatInfo(String.format(String.format("Exported \"%s\" successfully", name), name));
            extension.getLogger().log(String.format("Exported preset \"%s\" successfully", name), "green");

        }
        else {
            extension.sendVisualChatInfo("ERROR - Couldn't export due to unsufficient resources");
            extension.getLogger().log("Couldn't export due to unsufficient resources", "red");
        }
        reset();
    }

    private void requestWiredConfigsLoop(int x, int y, int dimX, int dimY) {
        try {
            // Phase 1: fetch wired configurations, retrying if some don't respond.
            int originalRemaining;
            synchronized (lock) {
                originalRemaining = unRegisteredWiredsInArea(x, y, dimX, dimY).size();
            }

            while (state == PresetExportState.FETCHING_UNKNOWN_CONFIGS) {
                LinkedList<Integer> linkedList;
                synchronized (lock) {
                    linkedList = new LinkedList<>(unRegisteredWiredsInArea(x, y, dimX, dimY));
                }

                while (state == PresetExportState.FETCHING_UNKNOWN_CONFIGS && linkedList.size() > 0) {
                    Integer wiredId = linkedList.pollFirst();
                    Utils.sleep(100);
                    extension.sendToServer(new HPacket("Open", HMessage.Direction.TOSERVER, wiredId));
                }

                if (state != PresetExportState.FETCHING_UNKNOWN_CONFIGS) return;

                Utils.sleep(300);

                int remainingLeft;
                synchronized (lock) {
                    remainingLeft = unRegisteredWiredsInArea(x, y, dimX, dimY).size();
                }

                if (remainingLeft == 0) break;
                if (remainingLeft <= originalRemaining / 2) {
                    extension.sendVisualChatInfo(String.format("WARNING - Did not retrieve all wired. Retrying %d missing wired..", remainingLeft));
                    extension.getLogger().log(String.format("Did not retrieve all wired. Retrying %d missing wired..", remainingLeft), "orange");
                    originalRemaining = remainingLeft;
                    continue;
                }

                synchronized (lock) {
                    extension.sendVisualChatInfo("ERROR - Couldn't export due to missing wired configurations");
                    extension.getLogger().log("Couldn't export due to missing wired configurations", "red");
                    reset();
                }
                return;
            }

            if (state != PresetExportState.FETCHING_UNKNOWN_CONFIGS) return;

            if (!extension.shouldExportFurniVariables()) {
                variableRequestPhaseStarted = true;
                lastVariableActivityTime = System.currentTimeMillis() - VARIABLE_GRACE_WINDOW_MS;
                synchronized (lock) {
                    if (state == PresetExportState.FETCHING_UNKNOWN_CONFIGS) {
                        maybeFinishExportAfterRetrieve();
                    }
                }
                return;
            }

            requestFurniVariablesInArea(x, y, dimX, dimY);

            for (int attempt = 0; attempt <= VARIABLE_MAX_RETRIES; attempt++) {
                while (state == PresetExportState.FETCHING_UNKNOWN_CONFIGS) {
                    Utils.sleep(300);
                    if (System.currentTimeMillis() - lastVariableActivityTime >= VARIABLE_GRACE_WINDOW_MS) break;
                }
                if (state != PresetExportState.FETCHING_UNKNOWN_CONFIGS) return;

                List<String> stillPending;
                synchronized (pendingVariableHolderRequests) {
                    stillPending = new ArrayList<>(pendingVariableHolderRequests);
                }

                if (stillPending.isEmpty() || attempt == VARIABLE_MAX_RETRIES) {
                    synchronized (lock) {
                        if (state == PresetExportState.FETCHING_UNKNOWN_CONFIGS) {
                            if (!stillPending.isEmpty()) {
                                extension.getLogger().log(String.format(
                                        "%d variable(s) did not reply after %d attempt(s)",
                                        stillPending.size(), attempt + 1), "orange");
                            }
                            pendingFurniVariableRequests.clear();
                            pendingVariableHolderRequests.clear();
                            maybeFinishExportAfterRetrieve();
                        }
                    }
                    return;
                }

                extension.getLogger().log(String.format(
                        "Retrying %d variable(s)..", stillPending.size()), "orange");
                lastVariableActivityTime = System.currentTimeMillis();
                for (String variableId : stillPending) {
                    if (state != PresetExportState.FETCHING_UNKNOWN_CONFIGS) return;
                    Utils.sleep((int) VARIABLE_REQUEST_INTERVAL_MS);
                    extension.sendToServer(new HPacket("WiredGetAllVariableHolders", HMessage.Direction.TOSERVER, variableId));
                    lastVariableActivityTime = System.currentTimeMillis();
                }
            }
        }
        catch (Exception e) {
            synchronized (lock) {
                extension.sendVisualChatInfo("ERROR - Something went wrong while fetching configurations..");
                extension.getLogger().log("Something went wrong while fetching configurations..", "red");
                reset();
            }
        }

    }

    private void maybeFinishExportAfterRetrieve() {
        synchronized (lock) {
            if (state == PresetExportState.FETCHING_UNKNOWN_CONFIGS) {
                if (!isReady()) {
                    reset();
                    return;
                }

                int x1 = rectCorner1.getX();
                int y1 = rectCorner1.getY();
                int x2 = rectCorner2.getX();
                int y2 = rectCorner2.getY();

                int dimX = Math.abs(x1 - x2) + 1;
                int dimY = Math.abs(y1 - y2) + 1;

                if (x1 > x2) x1 = x2;
                if (y1 > y2) y1 = y2;

                int remaining = unRegisteredWiredsInArea(x1, y1, dimX, dimY).size();
                long sinceVarActivity = System.currentTimeMillis() - lastVariableActivityTime;
                boolean varGraceElapsed = sinceVarActivity >= VARIABLE_GRACE_WINDOW_MS;

                if (remaining == 0 && hasVariableMap && variableRequestPhaseStarted && varGraceElapsed) {
                    pendingFurniVariableRequests.clear();
                    pendingVariableHolderRequests.clear();
                    export(exportName, x1, y1, dimX, dimY);
                }
                else if (remaining > 0 && remaining % 10 == 0) {
                    extension.getLogger().log(String.format("%d wired configurations left to retrieve..", remaining), "orange");
                }
            }
        }
    }

    private void maybeRetrieveBindings(int typeId, PresetWiredBase wired) {
        if (isReady()) {
            FurniDataTools furniData = extension.getFurniDataTools();
            String className = furniData.getFloorItemName(typeId);
            if (requireBindings.contains(className) && !wired.getStringConfig().isEmpty()) {

                List<PresetWiredFurniBinding> bindings = new ArrayList<>();

                String[] bindingsAsStrings = wired.getStringConfig().split(";");
                for (String bindingAsString : bindingsAsStrings) {
                    String[] fields = bindingAsString.split(",");

                    int bindFurniId = Integer.parseInt(fields[0]);
                    String state = fields[1].equals("N") ? null : fields[1];
                    Integer rotation = fields[2].equals("N") ? null : Integer.parseInt(fields[2]);
                    HPoint location = fields[3].equals("N") || fields[4].equals("N") ? null :
                            new HPoint(Integer.parseInt(fields[3]), Integer.parseInt(fields[4]));
                    Integer altitude = fields.length < 6 || fields[5].equals("N") ? null : Integer.parseInt(fields[5]);

                    PresetWiredFurniBinding binding = new PresetWiredFurniBinding(bindFurniId, wired.getWiredId(), location, rotation, state, altitude);
                    bindings.add(binding);
                }

                wiredFurniBindings.put(wiredCacheKey(wired.getWiredId()), bindings);
                wired.setStringConfig("");
            }
        }
    }

    private void retrieveActionConf(HMessage hMessage) {
        if (state == PresetExportState.FETCHING_UNKNOWN_CONFIGS) {
            hMessage.setBlocked(true);
            RetrievedWiredEffect effect = RetrievedWiredEffect.fromPacket(hMessage.getPacket());
            wiredEffectConfigs.put(wiredCacheKey(effect.getWiredId()), effect);

            maybeRetrieveBindings(effect.getTypeId(), effect);
            maybeFinishExportAfterRetrieve();
        }
    }

    private void retrieveAddonConf(HMessage hMessage) {
        if (state == PresetExportState.FETCHING_UNKNOWN_CONFIGS) {
            hMessage.setBlocked(true);
            RetrievedWiredAddon addon = RetrievedWiredAddon.fromPacket(hMessage.getPacket());
            wiredAddonConfigs.put(wiredCacheKey(addon.getWiredId()), addon);

            maybeRetrieveBindings(addon.getTypeId(), addon); // not needed
            maybeFinishExportAfterRetrieve();
        }
    }

    private void retrieveSelectorConf(HMessage hMessage) {
        if (state == PresetExportState.FETCHING_UNKNOWN_CONFIGS) {
            hMessage.setBlocked(true);
            RetrievedWiredSelector selector = RetrievedWiredSelector.fromPacket(hMessage.getPacket());
            wiredSelectorConfigs.put(wiredCacheKey(selector.getWiredId()), selector);

            maybeRetrieveBindings(selector.getTypeId(), selector); // not needed
            maybeFinishExportAfterRetrieve();
        }
    }

    private void retrieveVariableConf(HMessage hMessage) {
        if (state == PresetExportState.FETCHING_UNKNOWN_CONFIGS) {
            hMessage.setBlocked(true);
            RetrievedWiredVariable variable = RetrievedWiredVariable.fromPacket(hMessage.getPacket());
            wiredVariableConfigs.put(wiredCacheKey(variable.getWiredId()), variable);

            maybeRetrieveBindings(variable.getTypeId(), variable); // not needed
            maybeFinishExportAfterRetrieve();
        }
    }

    private void retrieveConditionConf(HMessage hMessage) {
        if (state == PresetExportState.FETCHING_UNKNOWN_CONFIGS) {
            hMessage.setBlocked(true);
            RetrievedWiredCondition cond = RetrievedWiredCondition.fromPacket(hMessage.getPacket());
            wiredConditionConfigs.put(wiredCacheKey(cond.getWiredId()), cond);

            maybeRetrieveBindings(cond.getTypeId(), cond);
            maybeFinishExportAfterRetrieve();
        }
    }

    private void retrieveTriggerConf(HMessage hMessage) {
        if (state == PresetExportState.FETCHING_UNKNOWN_CONFIGS) {
            hMessage.setBlocked(true);
            RetrievedWiredTrigger trig = RetrievedWiredTrigger.fromPacket(hMessage.getPacket());
            wiredTriggerConfigs.put(wiredCacheKey(trig.getWiredId()), trig);

            maybeRetrieveBindings(trig.getTypeId(), trig);
            maybeFinishExportAfterRetrieve();
        }
    }

    private void openWired(HMessage hMessage) {
        if (state == PresetExportState.FETCHING_UNKNOWN_CONFIGS) {
            hMessage.setBlocked(true);
            extension.sendVisualChatInfo("Do not open wired while the extension is fetching configurations");
        }
    }

    private void attemptExport(String name, int x, int y, int dimX, int dimY) {
        if (isReady()) {
            if (wallOnlyExport) {
                export(name, x, y, dimX, dimY);
                return;
            }

            furniVariablesByFurniId.clear();
            pendingFurniVariableRequests.clear();
            pendingVariableHolderRequests.clear();
            exportAreaFurniIds.clear();

            List<Integer> unregisteredWired = unRegisteredWiredsInArea(x, y, dimX, dimY);
            if (((!hasVariableMap && hasWiredVariables()) || unregisteredWired.size() > 0) && extension.shouldExportWired()) {
                exportName = name;
                state = PresetExportState.FETCHING_UNKNOWN_CONFIGS;
                hasVariableMap = false;
                variablesMap = new HashMap<>();
                variableIdToName = new HashMap<>();
                extension.sendToServer(new HPacket("WiredGetAllVariablesDiffs", HMessage.Direction.TOSERVER, 0));
                extension.sendVisualChatInfo(String.format(
                        "Fetching additional %s wired configurations before exporting... do not alter the room",
                        unregisteredWired.size()
                ));
                new Thread(() -> requestWiredConfigsLoop(x, y, dimX, dimY)).start();
            }
            else {
                // Still request variable catalog/object variable data for export even if wired configs are already complete.
                state = PresetExportState.FETCHING_UNKNOWN_CONFIGS;
                exportName = name;
                hasVariableMap = false;
                variablesMap = new HashMap<>();
                variableIdToName = new HashMap<>();

                extension.sendToServer(new HPacket("WiredGetAllVariablesDiffs", HMessage.Direction.TOSERVER, 0));
                new Thread(() -> requestWiredConfigsLoop(x, y, dimX, dimY)).start();
            }
        }
        else {
            reset();
            extension.sendVisualChatInfo("ERROR - Couldn't export due to missing floorstate or furnidata");
            extension.getLogger().log("Couldn't export due to missing floorstate or furnidata", "red");
        }
    }

    public boolean hasWiredVariables() {
        if(wiredVariableConfigs.size() > 0)
            return true;

        for(PresetWiredTrigger preset : wiredTriggerConfigs.values()) {
            if(preset.getVariableIds().size() > 0)
                return true;
        }


        for(PresetWiredAddon preset : wiredAddonConfigs.values()) {
            if(preset.getVariableIds().size() > 0)
                return true;
        }


        for(PresetWiredCondition preset : wiredConditionConfigs.values()) {
            if(preset.getVariableIds().size() > 0)
                return true;
        }


        for(PresetWiredEffect preset : wiredEffectConfigs.values()) {
            if(preset.getVariableIds().size() > 0)
                return true;
        }


        for(PresetWiredSelector preset : wiredSelectorConfigs.values()) {
            if(preset.getVariableIds().size() > 0)
                return true;
        }

        return false;
    }

    public PresetExportState getState() {
        return state;
    }


    // called from wired importer
    public void cacheWiredConfig(PresetWiredBase presetWiredBase) {

        FurniDataTools furniData = extension.getFurniDataTools();
        FloorState floorState = extension.getFloorState();
        if (furniData == null || floorState == null) return;
        HFloorItem floorItem = floorState.furniFromId(presetWiredBase.getWiredId());
        if (floorItem == null) return;
        int typeId = floorItem.getTypeId();

        String className = furniData.getFloorItemName(typeId);
        if (requireBindings.contains(className)) {
            return;
        }

        if (presetWiredBase instanceof PresetWiredCondition) {
            PresetWiredCondition condition = (PresetWiredCondition) presetWiredBase;
            wiredConditionConfigs.put(wiredCacheKey(condition.getWiredId()), condition);
        }
        if (presetWiredBase instanceof PresetWiredEffect) {
            PresetWiredEffect effect = (PresetWiredEffect) presetWiredBase;
            wiredEffectConfigs.put(wiredCacheKey(effect.getWiredId()), effect);
        }
        if (presetWiredBase instanceof PresetWiredTrigger) {
            PresetWiredTrigger trigger = (PresetWiredTrigger) presetWiredBase;
            wiredTriggerConfigs.put(wiredCacheKey(trigger.getWiredId()), trigger);
        }
        if (presetWiredBase instanceof PresetWiredAddon) {
            PresetWiredAddon addon = (PresetWiredAddon) presetWiredBase;
            wiredAddonConfigs.put(wiredCacheKey(addon.getWiredId()), addon);
        }
        if (presetWiredBase instanceof PresetWiredSelector) {
            PresetWiredSelector selector = (PresetWiredSelector) presetWiredBase;
            wiredSelectorConfigs.put(wiredCacheKey(selector.getWiredId()), selector);
        }
        if (presetWiredBase instanceof PresetWiredVariable) {
            PresetWiredVariable variable = (PresetWiredVariable) presetWiredBase;
            wiredVariableConfigs.put(wiredCacheKey(variable.getWiredId()), variable);
        }
    }

    private void onFurniRemoved(HMessage hMessage) {
        int furniId = Integer.parseInt(hMessage.getPacket().readString());
        String key = wiredCacheKey(furniId);

        wiredTriggerConfigs.remove(key);
        wiredConditionConfigs.remove(key);
        wiredEffectConfigs.remove(key);
        wiredSelectorConfigs.remove(key);
        wiredAddonConfigs.remove(key);
        wiredVariableConfigs.remove(key);
    }

    private String wiredCacheKey(int id) {
        return extension.getFloorState().getRoomId() + "-" + id;
    }

    private void requestFurniVariablesInArea(int x, int y, int dimX, int dimY) {
        FloorState floor = extension.getFloorState();
        if (floor == null) return;

        Set<Integer> areaFurniIds = new HashSet<>();
        for (int xi = x; xi < x + dimX; xi++) {
            for (int yi = y; yi < y + dimY; yi++) {
                floor.getFurniOnTile(xi, yi).forEach(item -> areaFurniIds.add(item.getId()));
            }
        }
        exportAreaFurniIds.clear();
        exportAreaFurniIds.addAll(areaFurniIds);

        List<String> variableIds;
        synchronized (variablesMap) {
            variableIds = new ArrayList<>(variablesMap.values());
        }

        pendingVariableHolderRequests.clear();
        pendingVariableHolderRequests.addAll(variableIds);
        totalVariableRequests = variableIds.size();
        sentVariableRequests = 0;
        lastVariableActivityTime = System.currentTimeMillis();
        variableRequestPhaseStarted = true;

        if (variableIds.isEmpty()) {
            extension.getLogger().log("No user-defined wired variables in this room.", "orange");
            maybeFinishExportAfterRetrieve();
            return;
        }

        extension.getLogger().log(String.format(
                "Fetching %d wired variable(s)..", variableIds.size()), "orange");

        for (String variableId : variableIds) {
            if (state != PresetExportState.FETCHING_UNKNOWN_CONFIGS) return;
            Utils.sleep((int) VARIABLE_REQUEST_INTERVAL_MS);
            extension.sendToServer(new HPacket("WiredGetAllVariableHolders", HMessage.Direction.TOSERVER, variableId));
            sentVariableRequests++;
            lastVariableActivityTime = System.currentTimeMillis();
        }
    }

    private Map<String, Integer> getExportableFurniVariables(int furniId) {
        Map<String, Integer> source = furniVariablesByFurniId.get(furniId);
        if (source == null || source.isEmpty()) return Collections.emptyMap();

        Map<String, Integer> exportable = new HashMap<>();
        source.forEach((idOrName, value) -> {
            String resolvedName = variableIdToName.getOrDefault(idOrName, idOrName);
            if (!isDefaultVariableName(resolvedName)) {
                exportable.put(resolvedName, value);
            }
        });
        return exportable;
    }

    private static boolean isDefaultVariableName(String name) {
        if (name == null || name.isEmpty()) return true;
        char c = name.charAt(0);
        return c == '@' || c == '~';
    }

    public void clearCache() {
        synchronized (lock) {
            if (state != PresetExportState.FETCHING_UNKNOWN_CONFIGS) {
                wiredTriggerConfigs.clear();
                wiredConditionConfigs.clear();
                wiredEffectConfigs.clear();
                wiredSelectorConfigs.clear();
                wiredAddonConfigs.clear();
                wiredVariableConfigs.clear();
                hasVariableMap = false;
                variablesMap = new HashMap<>();
                variableIdToName = new HashMap<>();
                furniVariablesByFurniId.clear();
                pendingFurniVariableRequests.clear();
                pendingVariableHolderRequests.clear();
                exportAreaFurniIds.clear();
                variableRequestPhaseStarted = false;
                lastVariableActivityTime = 0L;
            }
        }
    }
}
