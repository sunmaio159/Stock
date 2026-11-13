package com.aquila.watchlist;

import com.aquila.common.AdminApi;
import com.aquila.common.Result;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@ConditionalOnProperty(name = "aquila.database.enabled", havingValue = "true")
public class WatchlistController {
    private final WatchlistService watchlists;
    private final AdminApi api;

    public WatchlistController(WatchlistService watchlists, AdminApi api) {
        this.watchlists = watchlists;
        this.api = api;
    }

    @GetMapping("/watchlist/groups")
    public Result<List<WatchlistGroup>> groups(HttpServletRequest request) {
        return api.respond(request, watchlists::groups);
    }

    @PostMapping("/watchlist/groups")
    public Result<WatchlistGroup> createGroup(
            @RequestBody GroupPayload payload,
            HttpServletRequest request) {
        return api.respond(request, () -> watchlists.createGroup(payload.groupName()));
    }

    @DeleteMapping("/watchlist/groups/{groupId}")
    public Result<Boolean> deleteGroup(@PathVariable String groupId, HttpServletRequest request) {
        return api.respond(request, () -> {
            watchlists.deleteGroup(groupId);
            return true;
        });
    }

    @GetMapping("/watchlist/groups/{groupId}/items")
    public Result<List<WatchlistItem>> items(@PathVariable String groupId, HttpServletRequest request) {
        return api.respond(request, () -> watchlists.items(groupId));
    }

    @PostMapping("/watchlist/groups/{groupId}/items")
    public Result<WatchlistItem> addItem(
            @PathVariable String groupId,
            @RequestBody ItemPayload payload,
            HttpServletRequest request) {
        return api.respond(request,
                () -> watchlists.add(groupId, payload.securityCode(), payload.idempotencyKey()));
    }

    @DeleteMapping("/watchlist/items/{itemId}")
    public Result<Boolean> removeItem(@PathVariable String itemId, HttpServletRequest request) {
        return api.respond(request, () -> {
            watchlists.removeItem(itemId);
            return true;
        });
    }

    @PutMapping("/watchlist/groups/{groupId}/order")
    public Result<WatchlistGroup> reorder(
            @PathVariable String groupId,
            @RequestBody ReorderPayload payload,
            HttpServletRequest request) {
        return api.respond(request,
                () -> watchlists.reorder(groupId, payload.expectedVersion(), payload.itemIds()));
    }

    @GetMapping("/hotlist")
    public Result<List<HotlistItem>> hotlist(HttpServletRequest request) {
        return api.respond(request, watchlists::hotlist);
    }

    @PostMapping("/hotlist")
    public Result<HotlistItem> addHotlist(
            @RequestBody HotlistPayload payload,
            HttpServletRequest request) {
        return api.respond(request, () -> watchlists.addHotlist(payload.securityCode()));
    }

    @DeleteMapping("/hotlist/{itemId}")
    public Result<Boolean> removeHotlist(@PathVariable String itemId, HttpServletRequest request) {
        return api.respond(request, () -> {
            watchlists.removeHotlist(itemId);
            return true;
        });
    }

    public record GroupPayload(String groupName) {}

    public record ItemPayload(String securityCode, String idempotencyKey) {}

    public record ReorderPayload(long expectedVersion, List<String> itemIds) {}

    public record HotlistPayload(String securityCode) {}
}
