package game;

import extension.GPresets;
import extension.logger.Logger;
import furnidata.FurniDataTools;
import gearth.extensions.parsers.HProductType;
import gearth.extensions.parsers.catalog.HCatalogIndex;
import gearth.extensions.parsers.catalog.HCatalogPage;
import gearth.extensions.parsers.catalog.HCatalogPageIndex;
import gearth.extensions.parsers.catalog.HProduct;
import gearth.protocol.HMessage;
import gearth.protocol.HPacket;
import org.apache.commons.io.FileUtils;
import utils.Callback;
import utils.Utils;

import java.io.File;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class BCCatalog {

    public static class SingleFurniProduct {
        private final int pageId;
        private final int offerId;
        private final String extraParam;

        SingleFurniProduct(int pageId, int offerId, String extraParam) {
            this.pageId = pageId;
            this.offerId = offerId;
            this.extraParam = extraParam;
        }

        public int getPageId() {
            return pageId;
        }

        public int getOfferId() {
            return offerId;
        }

        public String getExtraParam() {
            return extraParam;
        }
    }

    public enum CatalogState {
        NONE,
        AWAITING_INDEX,
        COLLECTING_PAGES,
        COLLECTED
    }

    private Logger logger;
    private Callback stateChangeCallback;

    private final GPresets extension;
    private CatalogState state = CatalogState.NONE;

    // Floor items: typeId -> product (one per typeId, same as old behavior)
    private final Map<Integer, SingleFurniProduct> floorTypeIdToProduct = new HashMap<>();

    // Wall items: typeId -> (extraParam -> product) to support variants (e.g. poster "4", poster "13")
    private final Map<Integer, Map<String, SingleFurniProduct>> wallTypeIdToProducts = new HashMap<>();

    public BCCatalog(GPresets extension, Logger logger, Callback stateChangeCallback) {
        this.extension = extension;
        this.logger = logger;
        this.stateChangeCallback = stateChangeCallback;

        extension.intercept(HMessage.Direction.TOCLIENT, "CatalogIndex", this::onCatalogIndex);
        extension.intercept(HMessage.Direction.TOSERVER, "GetCatalogPage", (m) -> {
            if (state == CatalogState.COLLECTING_PAGES) {
                m.setBlocked(true);
            }
        });
        extension.intercept(HMessage.Direction.TOCLIENT, "CatalogPage", this::onCatalogPage);
    }

    private void onCatalogPage(HMessage hMessage) {
        if (state == CatalogState.COLLECTING_PAGES) {
            HCatalogPage page = new HCatalogPage(hMessage.getPacket());
            page.getOffers().forEach(catalogOffer -> {
                if (!catalogOffer.isPet() && catalogOffer.getProducts().size() == 1) {
                    HProduct product = catalogOffer.getProducts().get(0);

                    if (product.getProductType() == HProductType.FloorItem) {
                        synchronized (floorTypeIdToProduct) {
                            floorTypeIdToProduct.put(product.getFurniClassId(), new SingleFurniProduct(
                                    page.getPageId(),
                                    catalogOffer.getOfferId(),
                                    product.getExtraParam()
                            ));
                        }
                    } else if (product.getProductType() == HProductType.WallItem) {
                        synchronized (wallTypeIdToProducts) {
                            wallTypeIdToProducts
                                    .computeIfAbsent(product.getFurniClassId(), k -> new HashMap<>())
                                    .put(product.getExtraParam(), new SingleFurniProduct(
                                            page.getPageId(),
                                            catalogOffer.getOfferId(),
                                            product.getExtraParam()
                                    ));
                        }
                    }
                }
            });
        }
    }

    public SingleFurniProduct getFloorProduct(int typeId) {
        return floorTypeIdToProduct.get(typeId);
    }

    /**
     * Get a wall item product by typeId and extraParam (state/variant).
     * For items like "poster", each variant (state "4", "13", etc.) has a different offerId.
     */
    public SingleFurniProduct getWallProduct(int typeId, String extraParam) {
        Map<String, SingleFurniProduct> variants = wallTypeIdToProducts.get(typeId);
        if (variants == null) return null;

        // Try exact match first
        SingleFurniProduct product = variants.get(extraParam);
        if (product != null) return product;

        // If extraParam is empty or no match, try empty key or return any available
        if (variants.size() == 1) {
            return variants.values().iterator().next();
        }

        // Try empty extraParam as fallback
        return variants.get("");
    }

    /**
     * Get any wall item product by typeId (ignoring variant).
     * Useful when we don't care about the specific variant.
     */
    public SingleFurniProduct getAnyWallProduct(int typeId) {
        Map<String, SingleFurniProduct> variants = wallTypeIdToProducts.get(typeId);
        if (variants == null || variants.isEmpty()) return null;
        return variants.values().iterator().next();
    }

    private void fetchPagesLoop(List<Integer> pageIds, String saveHash) {
        logger.log(String.format("Scraping %d BC catalog pages..", pageIds.size()), "blue");

        int i = 0;
        while (state == CatalogState.COLLECTING_PAGES && i < pageIds.size()) {
            extension.sendToServer(new HPacket("GetCatalogPage", HMessage.Direction.TOSERVER,
                    pageIds.get(i), -1, "BUILDERS_CLUB"));

            Utils.sleep(180);

            i++;
            if (i % 10 == 0) {
                logger.log(String.format("Scraping page %d of %d", i, pageIds.size()), "blue");
            }
        }

        if (state == CatalogState.COLLECTING_PAGES) {
            new Thread(() -> {
                Utils.sleep(300);
                if (state == CatalogState.COLLECTING_PAGES) setState(CatalogState.COLLECTED);
                int totalProducts = floorTypeIdToProduct.size() +
                        wallTypeIdToProducts.values().stream().mapToInt(Map::size).sum();
                logger.log(String.format("Collected %d BC products (%d floor, %d wall variants)",
                        totalProducts, floorTypeIdToProduct.size(),
                        wallTypeIdToProducts.values().stream().mapToInt(Map::size).sum()), "blue");

                saveCatalog(saveHash);
            }).start();
        }
    }

    private void findRelevantPageIds(List<Integer> pageIds, HCatalogPageIndex node) {
        if (node.getPageId() != -1 && node.getOfferIds().size() > 0) {
            pageIds.add(node.getPageId());
        }
        node.getChildren().forEach((s) -> findRelevantPageIds(pageIds, s));
    }

    private void onCatalogIndex(HMessage hMessage) {
        if (state == CatalogState.AWAITING_INDEX) {
            HCatalogIndex index = new HCatalogIndex(hMessage.getPacket());
            if (index.getCatalogType().equals("BUILDERS_CLUB")) {
                List<Integer> relevantPageIds = new ArrayList<>();
                findRelevantPageIds(relevantPageIds, index.getRoot());

                setState(CatalogState.COLLECTING_PAGES);
                String hash = "" + relevantPageIds.stream().map(integer -> "" + integer).collect(Collectors.joining(",")).hashCode();
                if (loadCatalog(hash)) {
                    setState(CatalogState.COLLECTED);
                    int totalProducts = floorTypeIdToProduct.size() +
                            wallTypeIdToProducts.values().stream().mapToInt(Map::size).sum();
                    logger.log(String.format("Loaded %d BC products from cache (%d floor, %d wall variants)",
                            totalProducts, floorTypeIdToProduct.size(),
                            wallTypeIdToProducts.values().stream().mapToInt(Map::size).sum()), "blue");
                } else {
                    new Thread(() -> fetchPagesLoop(relevantPageIds, hash)).start();
                }
            }
        }
    }

    public void requestIndex() {
        clear();
        setState(CatalogState.AWAITING_INDEX);
        extension.sendToServer(new HPacket("GetCatalogIndex", HMessage.Direction.TOSERVER, "BUILDERS_CLUB"));
        stateChangeCallback.call();
    }

    private void setState(CatalogState state) {
        this.state = state;
        stateChangeCallback.call();
    }

    public void clear() {
        setState(CatalogState.NONE);
        floorTypeIdToProduct.clear();
        wallTypeIdToProducts.clear();
        stateChangeCallback.call();
    }

    public CatalogState getState() {
        return state;
    }

    private String fileNameForHash(String hash) throws URISyntaxException {
        String path = (new File(GPresets.class.getProtectionDomain().getCodeSource().getLocation().toURI()))
                .getParentFile().toString();
        String filename = "BC_CATALOG_" + hash + ".txt";

        File root = new File(Paths.get(path, "catalog").toString());
        if (!root.exists()) {
            root.mkdir();
        }

        return Paths.get(path, "catalog", filename).toString();
    }

    // return true if it worked
    private boolean saveCatalog(String hash) {
        if (state == CatalogState.COLLECTED && extension.furniDataReady()) {
            FurniDataTools furniDataTools = extension.getFurniDataTools();

            StringBuilder builder = new StringBuilder();

            // Save floor items: F\tname\tpageId\tofferId[\textraParam]
            for (Integer typeId : floorTypeIdToProduct.keySet()) {
                SingleFurniProduct product = floorTypeIdToProduct.get(typeId);
                String furniName = furniDataTools.getFloorItemName(typeId);
                if (furniName == null) continue;
                String entry = product.extraParam.isEmpty() ?
                        String.format("F\t%s\t%d\t%d", furniName, product.pageId, product.offerId) :
                        String.format("F\t%s\t%d\t%d\t%s", furniName, product.pageId, product.offerId, product.extraParam);

                builder.append(entry);
                builder.append("\n");
            }

            // Save wall items: W\tname\tpageId\tofferId\textraParam
            for (Integer typeId : wallTypeIdToProducts.keySet()) {
                Map<String, SingleFurniProduct> variants = wallTypeIdToProducts.get(typeId);
                String furniName = furniDataTools.getWallItemName(typeId);
                if (furniName == null) continue;

                for (SingleFurniProduct product : variants.values()) {
                    String entry = String.format("W\t%s\t%d\t%d\t%s", furniName, product.pageId, product.offerId, product.extraParam);
                    builder.append(entry);
                    builder.append("\n");
                }
            }

            try {
                FileUtils.writeStringToFile(new File(fileNameForHash(hash)), builder.toString(), StandardCharsets.UTF_8);
                return true;
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        return false;
    }

    // return true if it worked
    private boolean loadCatalog(String hash) {
        if (state == CatalogState.COLLECTING_PAGES && extension.furniDataReady()) {
            FurniDataTools furniDataTools = extension.getFurniDataTools();

            try {
                File file = new File(fileNameForHash(hash));
                if (file.exists() && !file.isDirectory()) {
                    String[] allLines = FileUtils.readFileToString(file, StandardCharsets.UTF_8).split("\n");
                    for (String line : allLines) {
                        if (line.isEmpty()) continue;

                        String[] fields = line.split("\t");
                        if (fields.length < 4) continue;

                        String type = fields[0];
                        String name = fields[1];
                        int pageId = Integer.parseInt(fields[2]);
                        int offerId = Integer.parseInt(fields[3]);
                        String extraParam = fields.length >= 5 ? fields[4] : "";

                        if ("F".equals(type)) {
                            Integer typeId = furniDataTools.getFloorTypeId(name);
                            if (typeId != null) {
                                floorTypeIdToProduct.put(typeId, new SingleFurniProduct(pageId, offerId, extraParam));
                            }
                        } else if ("W".equals(type)) {
                            Integer typeId = furniDataTools.getWallTypeId(name);
                            if (typeId != null) {
                                wallTypeIdToProducts
                                        .computeIfAbsent(typeId, k -> new HashMap<>())
                                        .put(extraParam, new SingleFurniProduct(pageId, offerId, extraParam));
                            }
                        }
                    }

                    return true;
                }

            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return false;
    }

    public void clearCache() throws URISyntaxException {
        String path = (new File(GPresets.class.getProtectionDomain().getCodeSource().getLocation().toURI()))
                .getParentFile().toString();
        File root = new File(Paths.get(path, "catalog").toString());
        if (root.exists() && root.listFiles() != null) {
            for (File file : root.listFiles()) {
                System.out.println(file);
                file.delete();
            }
        }
    }
}
