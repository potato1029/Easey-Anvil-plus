package com.mahdi.hotbartochest;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.AnvilScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.screen.AnvilScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Auto Anvil
 *
 * Client-side automation for applying enchanted books through
 * a real Anvil GUI.
 *
 * The server remains authoritative:
 * all inventory changes happen through normal ScreenHandler clicks.
 */
public class AutoAnvilClient implements ClientModInitializer {

    private static final String MOD_ID = "auto_anvil";

    // 6 ticks = 300 ms
    private static final int ACTION_DELAY_TICKS = 6;

    // Delay after opening the Anvil.
    private static final int OPEN_DELAY_TICKS = 8;

    // Message duration: 50 ticks = 2.5 seconds.
    private static final int MESSAGE_TICKS = 50;

    private static final KeyBinding.Category CATEGORY =
            KeyBinding.Category.create(
                    Identifier.of(MOD_ID, "controls")
            );

    private static KeyBinding toggleKey;
    private static KeyBinding targetMenuKey;

    /*
     * Saved ON/OFF state.
     */
    private static boolean enabled = true;

    /*
     * Item selected by the user.
     */
    private static Identifier targetItemId;

    private static int delay;

    private static boolean waitingForInputResult;
    private static boolean waitingForOutput;
    private static boolean waitingForClear;

    private static ItemStack currentTarget = ItemStack.EMPTY;
    private static ItemStack currentBook = ItemStack.EMPTY;

    private static AnvilScreenHandler lastHandler;

    private static int statusTicks;

    /*
     * Config file.
     */
    private static final Path CONFIG_FILE =
            Path.of("config", "auto_anvil.properties");

    @Override
    public void onInitializeClient() {

        loadConfig();

        /*
         * Toggle ON/OFF.
         *
         * Default = Unbound.
         * User can choose any key in Minecraft Controls.
         */
        toggleKey = KeyBindingHelper.registerKeyBinding(
                new KeyBinding(
                        "key.auto_anvil.toggle",
                        InputUtil.Type.KEYSYM,
                        GLFW.GLFW_KEY_K,
                        CATEGORY
                )
        );

        /*
         * Opens the Item Selector menu.
         *
         * Default key = U.
         * User can change it in Controls.
         */
        targetMenuKey = KeyBindingHelper.registerKeyBinding(
                new KeyBinding(
                        "key.auto_anvil.target_menu",
                        InputUtil.Type.KEYSYM,
                        GLFW.GLFW_KEY_U,
                        CATEGORY
                )
        );

        ClientTickEvents.END_CLIENT_TICK.register(
                AutoAnvilClient::tick
        );
    }

    private static void tick(MinecraftClient client) {

        /*
         * Handle message timer.
         */
        if (statusTicks > 0) {
            statusTicks--;

            if (statusTicks == 0 && client.player != null) {
                client.player.sendMessage(Text.empty(), true);
            }
        }

        /*
         * Toggle ON/OFF.
         */
        while (toggleKey.wasPressed()) {

            enabled = !enabled;

            resetOperation();
            saveConfig();

            if (enabled) {
                message(
                        client,
                        "Auto Anvil: ON",
                        0x55FF55
                );
            } else {
                message(
                        client,
                        "Auto Anvil: OFF",
                        0xFF5555
                );
            }
        }

        /*
         * Open target selector.
         */
        while (targetMenuKey.wasPressed()) {

            if (client.player != null
                    && !(client.currentScreen
                    instanceof TargetSelectionScreen)) {

                client.setScreen(
                        new TargetSelectionScreen(
                                client.currentScreen
                        )
                );
            }
        }

        /*
         * If disabled, stop here.
         */
        if (!enabled
                || client.player == null
                || client.interactionManager == null) {

            return;
        }

        /*
         * Auto Anvil only works while the Anvil GUI is open.
         */
        if (!(client.currentScreen instanceof AnvilScreen screen)) {

            resetOperation();
            return;
        }

        AnvilScreenHandler handler =
                screen.getScreenHandler();

        /*
         * Detect a newly opened Anvil.
         */
        if (handler != lastHandler) {

            lastHandler = handler;

            delay = OPEN_DELAY_TICKS;

            if (!waitingForInputResult
                    && !waitingForOutput) {

                currentTarget = ItemStack.EMPTY;
                currentBook = ItemStack.EMPTY;
            }
        }

        /*
         * Wait before doing anything.
         */
        if (delay > 0) {
            delay--;
            return;
        }

        /*
         * We have an output waiting to be taken.
         */
        if (waitingForOutput) {

            takeOutput(
                    client,
                    handler
            );

            return;
        }

        /*
         * Wait until the previous operation has completely cleared.
         */
        if (waitingForClear) {

            if (!handler
                    .getSlot(
                            AnvilScreenHandler.INPUT_1_ID
                    )
                    .getStack()
                    .isEmpty()
                    ||
                    !handler
                            .getSlot(
                                    AnvilScreenHandler.INPUT_2_ID
                            )
                            .getStack()
                            .isEmpty()
                    ||
                    !handler
                            .getSlot(
                                    AnvilScreenHandler.OUTPUT_ID
                            )
                            .getStack()
                            .isEmpty()) {

                delay = 1;
                return;
            }

            waitingForClear = false;
            delay = ACTION_DELAY_TICKS;

            return;
        }

        /*
         * Waiting for the first item to appear in the Anvil.
         */
        if (waitingForInputResult) {

            prepareOrCheckResult(
                    client,
                    handler
            );

            return;
        }

        /*
         * Start the next enchantment.
         */
        startNextOperation(
                client,
                handler
        );
    }

    private static void startNextOperation(
            MinecraftClient client,
            AnvilScreenHandler handler) {

        /*
         * User has not selected an item.
         */
        if (targetItemId == null) {

            message(
                    client,
                    "Auto Anvil: No target item selected",
                    0xFF55FF
            );

            return;
        }

        /*
         * Never touch an Anvil that already has items inside it.
         */
        if (!handler
                .getSlot(
                        AnvilScreenHandler.INPUT_1_ID
                )
                .getStack()
                .isEmpty()
                ||
                !handler
                        .getSlot(
                                AnvilScreenHandler.INPUT_2_ID
                        )
                        .getStack()
                        .isEmpty()) {

            stopWithError(
                    client,
                    "Auto Anvil: Anvil inputs are not empty"
            );

            return;
        }

        Item targetItem =
                Registries.ITEM.get(targetItemId);

        if (targetItem == null
                || targetItem == Items.AIR) {

            stopWithError(
                    client,
                    "Auto Anvil: Target item no longer exists"
            );

            return;
        }

        PlayerInventory inventory =
                client.player.getInventory();

        /*
         * Find the next selected item.
         */
        int targetInvSlot =
                findTargetSlot(
                        inventory,
                        targetItem
                );

        if (targetInvSlot < 0) {

            message(
                    client,
                    "Auto Anvil: No target items left",
                    0x55FF55
            );

            return;
        }

        ItemStack target =
                inventory.getStack(targetInvSlot);

        /*
         * Find a book that actually provides
         * a useful enchantment.
         */
        int bookInvSlot =
                findUsefulBook(
                        inventory,
                        target
                );

        if (bookInvSlot < 0) {

            message(
                    client,
                    "Auto Anvil: No useful enchanted book for this item",
                    0xFFAA00
            );

            return;
        }

        currentTarget =
                target.copy();

        currentBook =
                inventory
                        .getStack(bookInvSlot)
                        .copy();

        /*
         * Shift-click target into Anvil input 1.
         */
        client.interactionManager.clickSlot(
                handler.syncId,
                playerInventoryToHandlerSlot(
                        targetInvSlot
                ),
                0,
                SlotActionType.QUICK_MOVE,
                client.player
        );

        waitingForInputResult = true;

        delay = ACTION_DELAY_TICKS;
    }

    private static void prepareOrCheckResult(
            MinecraftClient client,
            AnvilScreenHandler handler) {

        ItemStack input1 =
                handler
                        .getSlot(
                                AnvilScreenHandler.INPUT_1_ID
                        )
                        .getStack();

        /*
         * Server has not synchronized yet.
         */
        if (input1.isEmpty()) {

            delay = 1;

            return;
        }

        PlayerInventory inventory =
                client.player.getInventory();

        /*
         * Find the exact book we selected earlier.
         */
        int bookSlot =
                findExactBook(
                        inventory,
                        currentBook
                );

        if (bookSlot < 0) {

            stopWithError(
                    client,
                    "Auto Anvil: Enchanted book disappeared"
            );

            return;
        }

        /*
         * Put the book into Anvil input 2.
         */
        client.interactionManager.clickSlot(
                handler.syncId,
                playerInventoryToHandlerSlot(
                        bookSlot
                ),
                0,
                SlotActionType.QUICK_MOVE,
                client.player
        );

        waitingForInputResult = false;
        waitingForOutput = true;

        delay = ACTION_DELAY_TICKS;
    }

    private static void takeOutput(
            MinecraftClient client,
            AnvilScreenHandler handler) {

        ItemStack output =
                handler
                        .getSlot(
                                AnvilScreenHandler.OUTPUT_ID
                        )
                        .getStack();

        /*
         * No output yet.
         */
        if (output.isEmpty()) {

            int cost =
                    handler.getLevelCost();

            if (cost > 39) {

                stopWithError(
                        client,
                        "Auto Anvil: Too Expensive!"
                );

            } else if (
                    cost > client.player.experienceLevel) {

                stopWithError(
                        client,
                        "Auto Anvil: Not Enough XP"
                );

            } else {

                delay = 1;
            }

            return;
        }

        int cost =
                handler.getLevelCost();

        /*
         * Too expensive.
         */
        if (cost > 39) {

            stopWithError(
                    client,
                    "Auto Anvil: Too Expensive!"
            );

            return;
        }

        /*
         * Not enough XP.
         */
        if (cost > client.player.experienceLevel) {

            stopWithError(
                    client,
                    "Auto Anvil: Not Enough XP"
            );

            return;
        }

        /*
         * Take the real Anvil output.
         *
         * This means the server remains authoritative.
         */
        client.interactionManager.clickSlot(
                handler.syncId,
                AnvilScreenHandler.OUTPUT_ID,
                0,
                SlotActionType.QUICK_MOVE,
                client.player
        );

        delay = ACTION_DELAY_TICKS;

        waitingForOutput = false;
        waitingForClear = true;

        message(
                client,
                "Auto Anvil: Enchanted",
                0x55FF55
        );
    }

    /*
     * Find the first item matching the selected target.
     */
    private static int findTargetSlot(
            PlayerInventory inventory,
            Item item) {

        for (int i = 0;
             i < inventory.size();
             i++) {

            ItemStack stack =
                    inventory.getStack(i);

            if (!stack.isEmpty()
                    && stack.getItem() == item) {

                return i;
            }
        }

        return -1;
    }

    /*
     * Find the exact enchanted book we previously selected.
     */
    private static int findExactBook(
            PlayerInventory inventory,
            ItemStack wanted) {

        for (int i = 0;
             i < inventory.size();
             i++) {

            ItemStack stack =
                    inventory.getStack(i);

            if (!stack.isEmpty()
                    && stack.getItem()
                    == Items.ENCHANTED_BOOK
                    && ItemStack
                    .areItemsAndComponentsEqual(
                            stack,
                            wanted
                    )) {

                return i;
            }
        }

        return -1;
    }

    /*
     * Find a book that contains at least one
     * enchantment that is useful for the target.
     */
    private static int findUsefulBook(
            PlayerInventory inventory,
            ItemStack target) {

        for (int i = 0;
             i < inventory.size();
             i++) {

            ItemStack book =
                    inventory.getStack(i);

            if (book.isEmpty()
                    || book.getItem()
                    != Items.ENCHANTED_BOOK) {

                continue;
            }

            if (hasUsefulEnchantment(
                    target,
                    book
            )) {

                return i;
            }
        }

        return -1;
    }

    /*
     * Determines whether the book has at least
     * one useful enchantment.
     *
     * Existing equal/higher enchantments are ignored.
     */
    private static boolean hasUsefulEnchantment(
            ItemStack target,
            ItemStack book) {

        var bookEnchants =
                EnchantmentHelper.getEnchantments(
                        book
                );

        var targetEnchants =
                EnchantmentHelper.getEnchantments(
                        target
                );

        for (
                RegistryEntry<Enchantment> candidate
                :
                bookEnchants.getEnchantments()
        ) {

            int bookLevel =
                    bookEnchants.getLevel(
                            candidate
                    );

            int existingLevel =
                    targetEnchants.getLevel(
                            candidate
                    );

            /*
             * Already has same or higher level.
             */
            if (bookLevel <= existingLevel) {
                continue;
            }

            Enchantment enchantment =
                    candidate.value();

            /*
             * Not supported by this item.
             */
            if (!enchantment.isSupportedItem(
                    target
            )) {

                continue;
            }

            /*
             * Check compatibility with existing enchantments.
             */
            boolean compatible = true;

            for (
                    RegistryEntry<Enchantment> existing
                    :
                    targetEnchants.getEnchantments()
            ) {

                if (!existing.equals(candidate)
                        && !Enchantment.canBeCombined(
                                candidate,
                                existing
                        )) {

                    compatible = false;
                    break;
                }
            }

            if (compatible) {
                return true;
            }
        }

        return false;
    }

    /*
     * Convert PlayerInventory slot number
     * to Anvil ScreenHandler slot number.
     *
     * Forging handlers use:
     * 3..38 for player inventory.
     */
    private static int playerInventoryToHandlerSlot(
            int inventorySlot) {

        return 3 + inventorySlot;
    }

    /*
     * Stop and save OFF state after an error.
     */
    private static void stopWithError(
            MinecraftClient client,
            String text) {

        enabled = false;

        resetOperation();

        saveConfig();

        message(
                client,
                text,
                0xFF5555
        );
    }

    /*
     * Reset current operation.
     */
    private static void resetOperation() {

        delay = 0;

        waitingForInputResult = false;
        waitingForOutput = false;
        waitingForClear = false;

        currentTarget = ItemStack.EMPTY;
        currentBook = ItemStack.EMPTY;

        lastHandler = null;
    }

    /*
     * Show a colored Action Bar message.
     */
    private static void message(
            MinecraftClient client,
            String text,
            int color) {

        if (client.player != null) {

            client.player.sendMessage(
                    Text.literal(text)
                            .styled(
                                    style ->
                                            style.withColor(color)
                            ),
                    true
            );

            statusTicks =
                    MESSAGE_TICKS;
        }
    }

    /*
     * Load saved configuration.
     */
    private static void loadConfig() {

        try {

            if (!Files.exists(CONFIG_FILE)) {
                return;
            }

            List<String> lines =
                    Files.readAllLines(
                            CONFIG_FILE
                    );

            for (String line : lines) {

                if (line.startsWith("enabled=")) {

                    enabled =
                            Boolean.parseBoolean(
                                    line.substring(
                                            "enabled=".length()
                                    )
                            );

                } else if (
                        line.startsWith("target=")) {

                    String value =
                            line.substring(
                                    "target=".length()
                            );

                    if (!value.isEmpty()) {

                        Identifier id =
                                Identifier.tryParse(
                                        value
                                );

                        if (id != null) {
                            targetItemId = id;
                        }
                    }
                }
            }

        } catch (IOException ignored) {
        }
    }

    /*
     * Save configuration.
     */
    private static void saveConfig() {

        try {

            Path parent =
                    CONFIG_FILE.getParent();

            if (parent != null) {
                Files.createDirectories(parent);
            }

            List<String> lines =
                    new ArrayList<>();

            lines.add(
                    "enabled=" + enabled
            );

            lines.add(
                    "target="
                            + (
                            targetItemId == null
                                    ? ""
                                    : targetItemId
                    )
            );

            Files.write(
                    CONFIG_FILE,
                    lines
            );

        } catch (IOException ignored) {
        }
    }

    /*
     * User selected a target item.
     */
    private static void selectTarget(
            MinecraftClient client,
            Item item) {

        targetItemId =
                Registries.ITEM.getId(
                        item
                );

        resetOperation();

        saveConfig();

        message(
                client,
                "Auto Anvil target: "
                        + item
                        .getName(
                                item.getDefaultStack()
                        )
                        .getString(),
                0xFF55FF
        );
    }

    /*
     * ============================================================
     * ITEM SELECTION MENU
     * ============================================================
     */
    private static class TargetSelectionScreen
            extends Screen {

        /*
         * Only 12 buttons per page.
         */
        private static final int ITEMS_PER_PAGE = 12;

        private final Screen parent;

        private final List<Item> items =
                new ArrayList<>();

        private int page = 0;

        protected TargetSelectionScreen(
                Screen parent) {

            super(
                    Text.translatable(
                            "screen.auto_anvil.target_selector"
                    )
            );

            this.parent = parent;
        }

        @Override
        protected void init() {

            super.init();

            clearChildren();

            items.clear();

            if (client == null
                    || client.player == null) {

                return;
            }

            /*
             * Create unique item list
             * from current inventory.
             */
            for (int i = 0;
                 i < client.player
                         .getInventory()
                         .size();
                 i++) {

                ItemStack stack =
                        client.player
                                .getInventory()
                                .getStack(i);

                if (!stack.isEmpty()
                        && stack.getItem()
                        != Items.AIR
                        && !items.contains(
                                stack.getItem()
                        )) {

                    items.add(
                            stack.getItem()
                    );
                }
            }

            int totalPages =
                    Math.max(
                            1,
                            (
                                    items.size()
                                            + ITEMS_PER_PAGE
                                            - 1
                            )
                                    / ITEMS_PER_PAGE
                    );

            if (page >= totalPages) {
                page = totalPages - 1;
            }

            int columns = 3;

            int buttonWidth = 190;
            int buttonHeight = 30;

            int gap = 8;

            int totalWidth =
                    columns * buttonWidth
                            + (
                            columns - 1
                    ) * gap;

            int startX =
                    (width - totalWidth) / 2;

            int startY = 55;

            int from =
                    page * ITEMS_PER_PAGE;

            int to =
                    Math.min(
                            from + ITEMS_PER_PAGE,
                            items.size()
                    );

            /*
             * Create item buttons.
             */
            for (int i = from;
                 i < to;
                 i++) {

                Item item =
                        items.get(i);

                int local =
                        i - from;

                int col =
                        local % columns;

                int row =
                        local / columns;

                int x =
                        startX
                                + col
                                * (
                                buttonWidth
                                        + gap
                        );

                int y =
                        startY
                                + row
                                * (
                                buttonHeight
                                        + gap
                        );

                addDrawableChild(
                        ButtonWidget.builder(
                                Text.literal(
                                        item
                                                .getName(
                                                        item.getDefaultStack()
                                                )
                                                .getString()
                                ),
                                button -> {

                                    selectTarget(
                                            client,
                                            item
                                    );

                                    client.setScreen(
                                            parent
                                    );
                                }
                        )
                                .dimensions(
                                        x,
                                        y,
                                        buttonWidth,
                                        buttonHeight
                                )
                                .build()
                );
            }

            /*
             * Previous page.
             */
            if (page > 0) {

                addDrawableChild(
                        ButtonWidget.builder(
                                Text.translatable(
                                        "screen.auto_anvil.previous"
                                ),
                                button -> {

                                    page--;

                                    /*
                                     * Correct Minecraft 1.21.11
                                     * screen reinitialization.
                                     */
                                    clearAndInit();
                                }
                        )
                                .dimensions(
                                        startX,
                                        height - 38,
                                        120,
                                        24
                                )
                                .build()
                );
            }

            /*
             * Next page.
             */
            if (page + 1 < totalPages) {

                addDrawableChild(
                        ButtonWidget.builder(
                                Text.translatable(
                                        "screen.auto_anvil.next"
                                ),
                                button -> {

                                    page++;

                                    /*
                                     * Correct Minecraft 1.21.11
                                     * screen reinitialization.
                                     */
                                    clearAndInit();
                                }
                        )
                                .dimensions(
                                        width
                                                - startX
                                                - 120,
                                        height - 38,
                                        120,
                                        24
                                )
                                .build()
                );
            }

            /*
             * Cancel button.
             */
            addDrawableChild(
                    ButtonWidget.builder(
                            Text.translatable(
                                    "gui.cancel"
                            ),
                            button ->
                                    client.setScreen(
                                            parent
                                    )
                    )
                            .dimensions(
                                    (width - 120) / 2,
                                    height - 38,
                                    120,
                                    24
                            )
                            .build()
            );
        }

        @Override
        public void render(
                DrawContext context,
                int mouseX,
                int mouseY,
                float delta) {

            // Do NOT call Screen.renderBackground() here on 1.21.11.
            // It can trigger the "Can only blur once per frame" crash.
            context.fill(0, 0, width, height, 0xC0101010);

            super.render(
                    context,
                    mouseX,
                    mouseY,
                    delta
            );

            /*
             * Title.
             */
            context.drawCenteredTextWithShadow(
                    textRenderer,
                    Text.translatable(
                            "screen.auto_anvil.target_selector"
                    ),
                    width / 2,
                    18,
                    0xFFFFFF
            );

            /*
             * Page number.
             */
            int totalPages =
                    Math.max(
                            1,
                            (
                                    items.size()
                                            + ITEMS_PER_PAGE
                                            - 1
                            )
                                    / ITEMS_PER_PAGE
                    );

            context.drawCenteredTextWithShadow(
                    textRenderer,
                    Text.translatable(
                            "screen.auto_anvil.page",
                            page + 1,
                            totalPages
                    ),
                    width / 2,
                    35,
                    0xAAAAAA
            );
        }

        @Override
        public void close() {

            if (client != null) {

                client.setScreen(
                        parent
                );
            }
        }

        @Override
        public boolean shouldPause() {
            return false;
        }
    }
}
