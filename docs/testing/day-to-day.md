# Day-to-day test cases

How an ordinary person uses Shelf over a few days: adding keepsakes from their photos, looking back at them, and tidying up. Each case lists what to do, what should happen, and what happened in the last run.

**Last run:** 4 October 2026, Shelf 0.5.0 release build, Android 15 emulator (Pixel-sized, 1080 × 2400), fresh install. Photos were nine Wikimedia Commons images put in the gallery: a teddy bear, a concert ticket stub, friendship bracelets, pressed flowers, a vintage postcard, a mountain lake, beach pebbles, a latte and a small shell engraving.

**Results:** 12 cases. 6 passed and 6 passed with issues; none failed. Findings are at the end, and every one has since been fixed (see **Fixed after this run**).

## The cases

### 1. First launch

1. Open Shelf for the first time.
2. Read the welcome screen and tap **Begin**.

**Expected:** the welcome screen explains the app in a sentence; after Begin, the desk shows one empty book and makes the next step obvious.

**Result: pass.** The desk says "photograph something you kept" and a hand-drawn "start here" arrow points at the red +. Small oddities: the empty book's badge says "0 things", and the first book is called "My first shelf" while everything else calls them books.

### 2. Add a keepsake from a photo

1. Tap **+**, choose the teddy bear photo.
2. Wait for Shelf to find it, then **Snip it out**.
3. **Use this sticker**, type a name and a line of story, **Stick it in**.

**Expected:** the bear is found and selected without help, cut out cleanly, and lands on the first page with its tag and story.

**Result: pass with issues.** Five taps plus typing. The cut-out is clean and the bear lands on the page with a stamp of today's date. Issues:
- The hint says "Good choice. Snip it out." when Shelf, not the person, made the choice.
- The review screen offers "Choose a different object" though only one was found, and a screen reader still hears "Photo. Tap an object to select it".
- The snipped preview has no ink outline, unlike every other sticker in the app.

### 3. Keep one thing from a photo of several, in a new book

1. Tap **+**, choose the friendship bracelets photo (five bracelets).
2. Tap one bracelet, snip it, name it.
3. Under **In which book**, tap **+ New book**, call it "Summer camp", **Make the book**, then **Stick it in**.

**Expected:** each bracelet is found separately; the new book is made without leaving the form and is chosen for you.

**Result: pass with issues.** All five bracelets were found, the new book was selected straight away, and the form switched to the new book's pattern. Issues:
- Only one bracelet can be kept per trip; keeping all five means picking the same photo five times.
- Long, thin things come out small on the page and in the chips under the photo.

### 4. Keep a whole photo

1. Tap **+**, choose the mountain lake.

**Expected:** with nothing to cut out, Shelf says so and keeps the whole photo as a print.

**Result: pass with issues.** It explains "There's no separate thing to cut out here" and shows a taped print. Issues:
- "Try the cutout again" is offered though trying again can't help.
- A wide panorama gets a very tall bottom border on its print.

### 5. Flat paper: a ticket stub

1. Tap **+**, choose the ticket stub, snip it, name it, save.

**Expected:** the ticket's edge is found even though it fills most of the photo.

**Result: pass.** Clean edge, lands on the page with its tag.

### 6. Change your mind halfway

1. Tap **+**, choose the pebbles photo.
2. Tap **✕**, then **Leave**.

**Expected:** Shelf asks once before throwing the photo away, then returns to where you started.

**Result: pass.** It asks "Leave without keeping it?" and returns to the same page. (The pebbles themselves show a model limit: the whole beach is treated as one object.)

### 7. Look after a thing

1. On the desk, swipe to a book and open it; tap the bear.
2. Tap **Favourite**.
3. Tap **✎**, correct the name, move it to another book, **Save changes**.
4. Clear the name and try to save.

**Expected:** favourite sticks; the edit saves and the thing moves; an empty name is refused with a clear message.

**Result: pass with issues.** All of that works, and the thing page with the original photo taped beside the sticker is a highlight. Issues:
- Favourites aren't shown anywhere else: no star on the page, no list, no filter.
- Moving the bear into Summer camp put it first on the page (things are ordered by when they were added), which reshuffled the other three.

### 8. Arrange a page

1. Open a page with four things; tap **✎** (Arrange).
2. Drag a thing to an empty corner; pick a different pattern; tap **✓**.

**Expected:** things move where you drop them, the pattern changes, and the layout is remembered.

**Result: pass with issues.**
- With four things, the automatic layout overlapped: one tag was hidden behind another, and a story ran over a photo. Dragging fixed it, but most people won't know they need to.
- A thing can be dragged partly off the edge of the page.

### 9. Look back with the calendar

1. Tap the calendar button; tap today.

**Expected:** today is ringed, its things are shown, and tapping it lays them all out.

**Result: pass with issues.** The day sheet shows all five things with their tags and opens any of them. Issue: today's red ring covers the "+2" badge in the corner of the day cell.

### 10. Get interrupted

1. Start adding the postcard from the calendar; type its name and story.
2. Go to the home screen, open another app, and let Android stop Shelf.
3. Come back to Shelf.

**Expected:** the half-finished keepsake is still there with its name and story.

**Result: pass with issues.** The draft came back intact and saved normally, landing on a new page 2. Issue: adding from the calendar quietly picks whichever book the desk last showed. The postcard went into Summer camp without my choosing it; you only see this by checking the chips.

### 11. Tidy up books

1. Book options → **Rename** the first book to "Garden".
2. Open a thing in it → **✎** → **Delete this thing** → confirm.
3. Book options → **Delete "Garden"** → confirm.

**Expected:** each change shows straight away; deletions are confirmed and say they can't be undone.

**Result: pass.** All three work and the confirmations are clear. Deleting a thing is well hidden, though: thing → ✎ → scroll → Delete.

### 12. Larger text, and a poor photo

1. Set Android's text size to 130% and look at the desk, a thing, and the edit form.
2. Add a tiny, low-resolution image (134 × 153 pixels).

**Expected:** nothing is cut off at the larger size; a poor photo either works or says why it might look rough.

**Result: pass with issues.** At 130%, every screen still fits and reads well. The tiny image was cut out but blown up into a blocky sticker with no warning.

## Findings

### What works well

- **It's clear what to do.** One red + on every screen, a "start here" arrow on day one, and step labels while adding.
- **Cut-outs are good.** The bear, ticket, bracelets and flowers were all found without help, and a single object is chosen for you.
- **Nothing is lost by accident.** Drafts survive Android stopping the app, leaving asks first, deleting says it can't be undone, and a missing name is explained.
- **Fallbacks are graceful.** A landscape becomes a taped print with a plain explanation.
- **The thing page and day sheet are delightful.** The original photo beside its sticker, and a day's things laid out with their tags.
- **It holds up at larger text sizes.**

### What to fix, most important first

1. **Pages get crowded and labels collide.** With four things, tags and stories overlap. Make the automatic layout keep tags and notes apart, or put three things on a page.
2. **One thing per photo.** Let people tick several objects and snip them all in one go, since many keepsake photos show a few things together.
3. **The book is chosen silently.** Adding from the desk or calendar files into the last-shown book. Name the book in the step heading ("Adding to Summer camp"), or ask when it isn't obvious.
4. **Today's ring hides the "+N" count** on the calendar. Move the badge or draw the ring inside the cell.
5. **Favourites go nowhere.** Show a star on favourite things on their page and on the calendar, or give them a place of their own.
6. **Moving a thing reshuffles the page it joins.** Put moved things at the end instead of by date added.
7. **Busy photos are fiddly to choose from.** Cut lines overlap and the chip row runs off-screen without a hint. Make the chosen object stand out more, and show that the chips scroll.
8. **Delete is hard to find.** Offer it from the thing page's menu too.
9. **Poor photos aren't flagged.** Warn when a photo is very small, or keep its sticker small on the page.
10. **Things can be dragged off the page.** Keep the whole sticker on the page.

### Small wording and polish

- "Good choice" when Shelf made the choice; "Choose a different object" with only one; "Try the cutout again" when there's nothing to cut.
- "My first shelf" in an app about books; "0 things" on an empty book.
- The snipped preview lacks the ink outline used elsewhere; its screen-reader label is out of date.
- Thin things and panoramas look small or oddly framed.

## Fixed after this run

| Finding | What changed |
| --- | --- |
| Crowded pages | Unarranged things go in a 2×2 grid under the title and date stamp, each sized so its sticker, tag and note fit its cell. Tags and notes now hang centred under their sticker. Anything arranged by hand stays put, and the others find room around it. |
| One thing per photo | Tap as many objects as you like; the button becomes **Snip out all 3**. You then name each in turn ("Name it (2 of 3)"), and they all stick into the book together. |
| Book chosen silently | Every adding screen says where it's going: "Into Summer camp · step 1 of 2". From the calendar, a new thing goes into the book you last added to. |
| Ring hides +N | Today's ring is drawn under the count. |
| Favourites go nowhere | A red star marks favourites on their page, on the calendar day, and on the day sheet; screen readers say "a favourite". |
| Moving reshuffles pages | A book keeps its things in the order they went into it, so a moved thing joins at the end. |
| Busy photos | Picked objects are drawn bold and solid while everything else dims. The chip row fades at its edge, and the status says how many were found and to swipe for more. |
| Delete buried | A delete button sits next to ✎ on every thing's page. |
| Poor photos | Small photos get a gentle note that the sticker may look rough. |
| Dragged off the page | Dragging stops with the whole sticker still on the page. |
| Wording and polish | "Good choice" only after you choose. "Choose a different object" only when there was more than one, and "Try the cutout again" only after a failure or timeout. The first book is "My first book". An empty book has no count badge. The snipped preview has ink outlines, and its screen-reader label is "Your sticker". Thin things get more room. Prints of panoramas get a border sized to their short side. |

New tests check that the automatic layout never overlaps four long-named things, and that a moved thing joins the end of its new book.

## Second run: 0.5.1

**Run:** 4 October 2026, Shelf 0.5.1 release build, same emulator and photos, fresh install.

**Results:** all 12 cases passed, 7 of them cleanly; 5 have smaller issues left. Every finding from the first run is fixed. There were no crashes.

| Case | Result | Notes |
| --- | --- | --- |
| 1. First launch | Pass | "My first book", no "0 things" badge on the cover. |
| 2. Add a keepsake | Pass with issue | "Found it" instead of "Good choice"; no "Choose a different object" with one thing. **The story under a thing in the left column runs off the left edge of the screen.** |
| 3. Several from one photo, new book | Pass with issues | Picked 2 of 5 bracelets, named each in turn; the new book stayed chosen for the second. Says "Swipe the row for more" when all five fit; the button reads "Snip out all 2". |
| 4. Whole photo | Pass | Only "Use this photo" and "Choose another photo"; the panorama's border is neat. |
| 5. Ticket stub | Pass | Four things on one page, none overlapping. |
| 6. Cancel halfway | Pass | |
| 7. Look after a thing | Pass | Favourite star on the page, calendar and day sheet; the moved bear joined the end of its new book. |
| 8. Arrange a page | Pass with issue | The sticker stops at the page edge, but its story and favourite star can still hang off it. |
| 9. Calendar | Pass | "+2" now shows above today's ring, with a star for the favourite. |
| 10. Interrupted | Pass | "Into Summer camp" shown up front; the draft and its story survived; the postcard found room around the hand-placed bear. |
| 11. Tidy up | Pass | Delete from a thing's page: one tap plus a confirmation. |
| 12. Larger text, poor photo | Pass with issue | The small-photo warning shows. At 130% the thing page's top label almost touches the delete button. |

### Left to fix

1. **Notes run off the screen.** A story is centred under its sticker and is wider than the space left of a thing in the left column; it's also not kept on the page while dragging. Keep the whole footprint (sticker, tag and note) inside the page, both when laying out and when dragging.
2. **Doodles land on things.** Page doodles are placed without looking at the stamp, tags or stickers, so a star can sit on a tag. Place doodles only in empty space.
3. **Count wording.** "Snip out all 2" should be "Snip out both"; "Swipe the row for more" should only show when the row actually scrolls.
4. **Tight top label** on a thing's page at large text sizes.
5. **One page dot** shows under a single book; hide the dots when there's only one.
6. **Empty space on pages.** Things sit in the top part of each grid cell, which leaves a tall empty band at the bottom of the page.

### Fixed after the second run

| Finding | What changed |
| --- | --- |
| Notes off the screen | Tags and notes are at most 150 units wide, so two fit side by side, and the columns are spaced for them. Anything that would still reach the edge is nudged in, including things arranged by hand earlier. Dragging keeps the sticker, its star, tag and note on the page. |
| Doodles on things | Doodles are placed last, only where nothing else is: off the title, the stamp, every thing and its labels, and each other. |
| Count wording | "Snip out both" for two. "Swipe the row for more" only shows when the row really scrolls. |
| Tight top label | The label leaves more room for the corner buttons and shrinks to fit at large text sizes. |
| One page dot | No dots under a single book. |
| Empty band | Each thing is centred in its grid cell, so pages fill evenly. |

The layout test now also checks that four long-named things with stories keep their tags and notes inside the page.

## Notes on the test setup

- **Gboard on the emulator** treats mouse clicks as a pen and shows a handwriting strip instead of keys. Switch with ☰ → **Show on-screen keyboard**. Real phones aren't affected.
- **Speed** isn't judged here. The emulator was rendering in software, and finding objects took 10–15 seconds; a real phone should be quicker.
