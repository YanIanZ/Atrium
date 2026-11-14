package dev.yanianz.atrium.module.chat;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.view.AnvilView;

/** Filters event-owned content only. No world scans, inventory timers or cross-region access. */
final class ChatContentListener implements Listener {
    private final ChatModule module;

    ChatContentListener(ChatModule module) {
        this.module = module;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSign(SignChangeEvent event) {
        if (!module.contentSettings().signs) return;
        List<Component> original = event.lines();
        List<Component> filtered = new ArrayList<>(original.size());
        for (Component line : original) {
            String raw = plain(line);
            String text = module.filterContent(event.getPlayer(), raw);
            if (text == null) {
                event.setCancelled(true);
                return;
            }
            filtered.add(rewrite(line, text));
        }
        for (int i = 0; i < filtered.size(); i++) event.line(i, filtered.get(i));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBook(PlayerEditBookEvent event) {
        if (!module.contentSettings().books) return;
        BookMeta meta = event.getNewBookMeta();
        List<Component> pages = new ArrayList<>();
        for (Component page : meta.pages()) {
            String raw = plain(page);
            String text = module.filterContent(event.getPlayer(), raw);
            if (text == null) {
                event.setCancelled(true);
                return;
            }
            pages.add(rewrite(page, text));
        }
        // A title exists only on a signed book. The author and other metadata are left intact.
        if (event.isSigning() && meta.hasTitle()) {
            String raw = plain(meta.title());
            String text = module.filterContent(event.getPlayer(), raw);
            if (text == null || text.length() > 32) {
                event.setCancelled(true);
                return;
            }
            if (!text.equals(raw)) meta.title(Component.text(text));
        }
        meta.pages(pages);
        event.setNewBookMeta(meta);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAnvilPreview(PrepareAnvilEvent event) {
        if (!module.contentSettings().anvils || !(event.getView().getPlayer() instanceof Player player)) return;
        ItemStack result = event.getResult();
        if (result == null || result.getType().isAir()) return;
        ItemMeta meta = result.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) return;
        String raw = plain(meta.displayName());
        String text = module.previewContent(player, raw);
        if (text == null) {
            event.setResult(null);
        } else if (!text.equals(raw)) {
            ItemStack rewritten = result.clone();
            meta.displayName(Component.text(text));
            rewritten.setItemMeta(meta);
            event.setResult(rewritten);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAnvilTake(InventoryClickEvent event) {
        if (!module.contentSettings().anvils || !(event.getView() instanceof AnvilView)
                || event.getRawSlot() != 2 || !(event.getWhoClicked() instanceof Player player)) return;
        ItemStack result = event.getCurrentItem();
        if (result == null || result.getType().isAir()) return;
        ItemMeta meta = result.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) return;
        String raw = plain(meta.displayName());
        String text = module.filterContent(player, raw);
        // Another listener may replace the preview. Reject a stale/uncensored result without touching
        // input items, XP, the cursor or the inventory transaction.
        if (text == null || !text.equals(raw)) event.setCancelled(true);
    }

    /** Keep trusted existing styling only while the visible text is unchanged. Replacements are literal. */
    static Component rewrite(Component original, String text) {
        return text.equals(plain(original)) && original != null ? original : Component.text(text);
    }

    private static String plain(Component component) {
        return component == null ? "" : PlainTextComponentSerializer.plainText().serialize(component);
    }
}
