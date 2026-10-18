package com.newtonite.ops.items;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.newtonite.ops.auth.CurrentUser;
import com.newtonite.ops.auth.User;
import com.newtonite.ops.items.ItemService.ItemEdit;
import com.newtonite.ops.items.ItemService.NewItem;
import com.newtonite.ops.items.ItemViews.ItemDetail;
import com.newtonite.ops.items.ItemViews.ItemView;
import com.newtonite.ops.items.Workflow.Action;
import com.newtonite.ops.web.Idempotency;

@RestController
@RequestMapping("/api/items")
public class ItemController {

	public record ItemList(List<ItemView> items) {
	}

	public record NewComment(@NotBlank @Size(max = 2000) String body) {
	}

	private final ItemService items;
	private final Idempotency idempotency;

	public ItemController(ItemService items, Idempotency idempotency) {
		this.items = items;
		this.idempotency = idempotency;
	}

	@GetMapping
	public ItemList list(@CurrentUser User me,
			@RequestParam(required = false) String q,
			@RequestParam(required = false) Long teamId,
			@RequestParam(required = false) String status,
			@RequestParam(required = false) String assigned,
			@RequestParam(required = false) String sort) {
		return new ItemList(items.list(me, new ItemSearch.Filter(q, teamId, status, assigned, sort)));
	}

	@PostMapping
	public ResponseEntity<?> create(@CurrentUser User me,
			@RequestHeader(name = "Idempotency-Key", required = false) String key,
			@Valid @RequestBody NewItem body) {
		return idempotency.run(me, key, "POST /api/items",
				() -> ResponseEntity.status(HttpStatus.CREATED).body(items.create(me, body)));
	}

	@GetMapping("/{id}")
	public ItemDetail get(@CurrentUser User me, @PathVariable long id) {
		return items.detail(me, id);
	}

	@PatchMapping("/{id}")
	public ItemView update(@CurrentUser User me, @PathVariable long id, @Valid @RequestBody ItemEdit body) {
		try {
			return items.update(me, id, body);
		}
		catch (ConcurrencyFailureException e) {
			// Someone saved between our version check and our write.
			throw items.conflictWithCurrent(me, id);
		}
	}

	@PostMapping("/{id}/claim")
	public ItemView claim(@CurrentUser User me, @PathVariable long id) {
		return items.claim(me, id);
	}

	@PostMapping("/{id}/actions/{action}")
	public ItemView act(@CurrentUser User me, @PathVariable long id, @PathVariable String action) {
		return items.act(me, id, Action.parse(action));
	}

	@PostMapping("/{id}/comments")
	public ResponseEntity<?> comment(@CurrentUser User me, @PathVariable long id,
			@RequestHeader(name = "Idempotency-Key", required = false) String key,
			@Valid @RequestBody NewComment body) {
		return idempotency.run(me, key, "POST /api/items/" + id + "/comments",
				() -> ResponseEntity.status(HttpStatus.CREATED).body(items.comment(me, id, body.body())));
	}
}
