# Changelog: before 2.0

## [1.24.16] - 2026-10-13

### Changed
- Seated train riders crossing portals verified in game (1.24.15: no flash, no view behind the portal). The per-frame trace of 1.24.13 is off unless `-Dip_aeronautics.debug.riderTrace=true`; "seated on carriage at once" logs at debug.

## [1.24.15] - 2026-10-13

### Fixed
- Seated players passing through a portal (trains, and ships straddling a portal) no longer see the inside of their own head for a frame. While the view looks through the portal behind the seat, IP drew the player's own body in its own world again, around the camera; it is not drawn in that view any more (the hand still is).

## [1.24.14] - 2026-10-13

### Fixed
- Create trains: the flash of the other side of the portal when passing through seated. Your client's train runs a little behind the server's, so right after you change over your head is still behind the portal for a few frames; the view was meant to go through the portal then, but looked for the wrong face of the nether portal and never did.
- A position update from the server while you are seated on a crossing carriage no longer puts the camera at the server's spot (a little off) for a tick: you go straight back on your seat.

## [1.24.13] - 2026-10-13

### Fixed
- Create trains: when a seated player changed over to the other side of a portal, their client placed them for up to a tick where the server had them (a little ahead, past the portal) before putting them back on the seat as their train is drawn. They are now placed on the seat at once (logged: `[IPTrainRider] client: seated on carriage ...`).

### Added
- Diagnostics for the brief flash seated players still see when passing through: while a seated player's head is within two blocks of the carriage's portal, and for a second after they change dimension or seat, every frame is logged (`[IPTrainRiderTrace]`: head position, depth past the portal, whether the view went through the portal).

## [1.24.12] - 2026-10-13

### Fixed
- Create trains: a player seated on a carriage crossing a portal now changes over to the other side as their head passes the portal, instead of when Create's whole-block cut reaches their seat. Before, the head went past the portal while the player was still in the old dimension: they saw that world behind the portal, and others saw them sticking out of it. The look is turned with the portal, and the player is seated again in the same tick.
- For the few ticks your client's train lags the server's (and at a carriage's ends, before or after Create has a copy on the other side), a seated player whose head is past the portal looks and aims through it, as on ships.

## [1.24.11] - 2026-10-13

### Fixed
- Create trains: purple glass and a half-invisible carriage can no longer appear. On your client a carriage is never drawn with Create's own portal cut any more; it is drawn whole, and cut exactly at the portal plane while it crosses one. The log notes (`[IPTrainCrossing] ... but no crossing (drawn whole)`) the situation that used to show the glass, to find out why it happens.
- A carriage is no longer drawn a second time by Create's non-Flywheel renderer while Flywheel draws it (logged once if it would have been).

## [1.24.10] - 2026-10-13

### Fixed
- Create trains: the purple glass column could still appear. A carriage's model is rebuilt only when Flywheel next updates it, which it does less often for things further from the camera, while the view through a portal can draw it before that. A carriage whose model is out of date is now not drawn until it has been rebuilt, and the log says when that happens (`[IPTrainCrossing] ... not drawn until rebuilt`).

## [1.24.9] - 2026-10-13

### Fixed
- Create trains: the purple glass column with half a carriage missing after some trips through a portal. Create gives a carriage's new copy on the far side the cut left over from an earlier crossing, and builds its model later, at a moment 1.24.6 could miss. A carriage's model is now checked every frame against what it should show and rebuilt when they differ.

## [1.24.8] - 2026-10-13

### Changed
- Diagnostics for the purple glass and half-invisible carriage that can still appear after a few trips through a portal: the log says (at info, `[IPTrainCrossing]`) why a carriage falls back to Create's own portal cut, with the carriage's state in each dimension.
- A second copy of a carriage in the same dimension, which Create no longer updates, is no longer drawn.

## [1.24.7] - 2026-10-13

### Fixed
- The thin seam between the two halves of a Create carriage crossing a portal. The half on your side ended 1 cm before the portal while the half seen through it starts exactly at the portal; both now meet in the portal plane.

## [1.24.6] - 2026-10-13

### Fixed
- Create trains after a few trips through a portal: no more purple glass in the middle of a carriage with half of it invisible (the other half on the way back). Create gave a carriage's new copy the cut left over from an earlier crossing, and its block model was built with that cut and never rebuilt. A carriage's model is now rebuilt whenever what it should show changes, and only then.

## [1.24.5] - 2026-10-13

### Fixed
- Create trains entering a portal: the brief jump at the carriage's front as its front wheels reach the portal. Train controls and blaze burners are drawn differently from the rest of the carriage, and the far side's copy drew them at the spot where the server had just created it, a little ahead of the train, before it was placed. A copy is now placed through the portal from the moment it exists. Those parts are drawn only by a placed copy, and only on its own side of the portal.
- Riding a train through a portal (not tested yet): the hand-over to the far side no longer moves you to the track at the far portal for a moment; you go straight to your seat's position on the far side and are seated again in the same tick.

## [1.24.4] - 2026-10-13

### Fixed
- The last brief jump of a Create train entering a portal. When Create created the carriage's copy on the far side, that copy's first update set the whole carriage, including the part you are looking at, to the server's position, which runs a little ahead of what your client shows; the train jumped forward and then settled. The new copy now joins the carriage as it is on your client.

## [1.24.3] - 2026-10-13

### Fixed
- Create trains through a portal: the part on the far side no longer jumps ahead for a moment when the carriage starts crossing, and the rear no longer freezes and gets "sucked in" when it finishes. The far side's carriage copy was shown on its very first tick, when it is drawn standing still (up to a block ahead at full speed); it is now shown only after it has been moving for two ticks, and until then the near copy keeps drawing the far side. The copy Create keeps for a few ticks after a carriage has left a dimension no longer moves, so it is no longer drawn; the carriage's other copy draws that end through the portal instead.

## [1.24.2] - 2026-10-13

### Fixed
- Server crash "Loading entity NBT: Entity has invalid position" with a Create train at a portal. When a wheel of a carriage stood exactly on the portal track's last point (here a one-bogey carriage reversing into the overworld), Create's placement of the carriage's other-dimension part divided by zero and the carriage's position became NaN. That case is now computed from the track's direction, and a carriage copy is never created at an invalid position. Worlds saved after such a crash load normally.

## [1.24.1] - 2026-10-13

### Fixed
- Create trains no longer lurch into a portal. Lining the two halves of a carriage up through the portal means making up about one block (1.23.0), and each carriage made it up while it was crossing: over a few blocks it ran about 20% faster and pulled away from the carriage behind, which looked like the train being sucked in, worst at speed. The block is now made up gradually over the track on both sides of the portal (24 blocks each way), a few percent of the train's speed, so the halves still meet exactly through the portal at every point.
- Fewer frame hitches when a train goes through a portal: carriages no longer rebuild their block model when they reach the portal, only when Create itself was hiding part of them. All carriages reaching through the same portal are drawn on the far side in one pass.

## [1.24.0] - 2026-10-13

### Changed
- Create trains: the front of a carriage no longer disappears into the portal before it shows up on the other side, and its rear no longer sticks out behind the portal after the carriage has crossed. Create only splits a carriage between the two dimensions once its first wheels cross (and joins it again once its last wheels have); until then the part already through the portal had nothing drawing it on the far side. That part is now drawn on the far side through the portal, the way the far half of a ship straddling a portal is, and cut away on the near side.
- A far-side copy of a carriage that the server sends before your client has the carriage crossing is kept hidden until it is placed, instead of standing still at the portal for a moment.

## [1.23.1] - 2026-10-13

### Fixed
- A Create train entering a portal no longer shows a copy of the carriage a block too far on, behind the portal, for a moment. The far half was created at Create's own position and stayed there, uncut, until its own sync data arrived; it is now spawned at the lined-up position and follows the carriage from its first tick. The same applies to the half left behind, which Create keeps for a few ticks after the carriage has fully crossed.

## [1.23.0] - 2026-10-13

### Changed
- Create trains cross portals without the purple glass. While a carriage is half through a portal, each dimension's half is now drawn whole and cut exactly at the portal plane, bogeys included, instead of Create's whole-block cut with a slice of purple stained glass and portal particles. Nothing sticks out behind or beside the portal frame.
- The two halves now line up through the portal. Create treats the step between the two portal tracks as zero length, but through an IP portal the far track starts about a block further on, so the far half ran a block ahead of the near one (Create's glass hid the overlap). While a carriage crosses, each half is moved towards the portal by its share of that block, so the halves meet at every point of the crossing and nothing jumps when one appears or goes away. Riders, couplings and collisions move with them.
- `-Dip_aeronautics.stockTrains=true` brings Create's own behaviour back, for comparison.

### Known gaps
- The carriage's ends beyond its outer wheels: the front end goes into the portal before Create splits the carriage and does not show on the far side until the first wheels cross (the same happens to the rear end afterwards).
- Seated riders are still handed over to the far half by Create (brief jump at the moment of the hand-over).

## [1.22.5] - 2026-10-13

### Fixed
- Jumping into a stronghold end portal no longer sets the player on fire. IP made players fire immune only while they touch the portal, but the server stops counting the contact once the player's eye is past the portal, a tick before the teleport, when the feet are already in the lava pool below. The immunity now lasts 10 ticks past the last contact.

## [1.22.4] - 2026-10-13

### Fixed
- Create tracks through a portal: no more seam in the rails on the track's side, and no rail outline on the portal's back. The outline was the last centimetre of the other dimension's track: in portal views IP keeps terrain up to 1 cm in front of the far portal (for portals lying on block faces); for world terrain behind portals lying in the middle of a block, such as nether portals, the cut is now exactly at the portal. Straddling ships keep their overlap. The track model is Create's again, without the rails' end faces lying in the portal (1.22.1); the depth change of 1.22.3, which caused the seam, is gone.

## [1.22.3] - 2026-10-13

### Fixed
- Create tracks through a portal had a small seam in the rails on the track's side (1.22.2 ended them 1 cm before the portal). The track model is Create's own again; instead the portal surface now wins where something lies exactly in it, so the rails meet seamlessly from the front and nothing shows on the portal's back.

## [1.22.2] - 2026-10-13

### Fixed
- The edges of a Create track's rails still showed as thin lines on the back of a portal (1.22.1 only removed the flickering end faces). The track now ends 1 cm before the portal surface, its end faces included.

## [1.22.1] - 2026-10-13

### Fixed
- Create train tracks running into a portal no longer flicker on the portal's surface when the portal is seen from the other side: the rails of Create's portal track ended exactly in the portal plane, and their end faces fought with the portal view. Those faces are no longer drawn; the track still meets its continuation seamlessly.

## [1.22.0] - 2026-10-13

### Added
- Shield Generators (the force-field mod for Sable ships) works with ships straddling a portal: the field of a ship half through a portal now also covers the part on the other side, and projectiles fired at that part bounce off the field there, with the flash, sound and sparks where they hit.

### Fixed
- The force field of a ship straddling a portal no longer shows past the portal on the ship's own side (beside or behind the portal frame), and no longer shows in front of the portal when seen through it. Its scan lines are cut at the portal too.
- Shooting a shielded ship through a portal now shows the flash on the field and the sparks from the shooter's side.
- A ship straddling a portal no longer has an invisible part of its shield behind the portal on its own side: projectiles flying there passed through the ship's blocks but bounced off the shield.

### Changed
- Particles made by the server (hit sparks, crits, cannon sparks) can now be seen through portals from another dimension, like sounds already could.

## [1.21.4] - 2026-10-13

### Fixed
- A ship going into a portal could bump a ship resting half through the same portal from the other side and push it out. The two ships never touched; the rope tying the resting ship to its balloon did: the part of a rope already through the portal still sat in its own dimension, where the other ship was coming in. Rope pieces through a portal no longer collide with ships on the far side of the portal (they still rest on the ground there, as before).
- Flying into a portal sometimes turned the player into a crouch for a moment, as if the blocks behind the portal were in the way. The check that decides whether a player has room to stand now ignores what is behind the portal, as Immersive Portals intended (this part of IP had been switched off in the original Immersive Aeronautics because it clashed with Sable; it now works alongside Sable's own check for ships).

## [1.21.3] - 2026-10-13

### Fixed
- The hot air glow inside a balloon of a ship straddling a portal is now cut at the portal like the ship itself. It still showed whole in the ship's own dimension, so the part of the balloon already through the portal glowed behind the portal where none of the ship is. (Seen through a portal, balloons still have no glow, as before.)

## [1.21.2] - 2026-10-13

### Fixed
- Stepping more than 15 blocks back from a portal shrank the area of the other dimension loaded behind it from two thirds to a third of the render distance (Immersive Portals' own rule): within a second the terrain seen through the portal ended much closer, and with Distant Horizons it turned into coarser LODs or holes a few chunks past the far portal. The loaded area now stays at two thirds of the render distance at any distance (the existing caps of 8 chunks, and fewer on a lagging server or client, still apply).

## [1.21.1] - 2026-10-13

### Fixed
- With Distant Horizons, looking through a portal from certain spots near it (a column of positions, about a block wide) turned on vanilla's thick fog in the world behind the portal, or made the LODs there look wrong. This happened wherever the view's camera, on the far side of the portal, ended up inside flowing or falling lava or water (e.g. a lavafall behind a nether portal), which DH took as "camera in a fluid". Fluids at the far-side camera are now ignored, as IP already does for the camera's fluid check.

## [1.21.0] - 2026-10-13

### Changed
- A player seated on a ship going through a portal now goes through with their seat as soon as their head passes the portal: they are in the destination dimension, still seated where the seat is (on a stand-in seat that follows it), and can get off onto the part of the ship already through. When the ship crosses fully they sit on the real seat again; if the ship backs out, they come back with it. Replaces 1.20.5's view-only change, which left a player who got off early behind the portal in the ship's own dimension.

## [1.20.5] - 2026-10-13

### Fixed
- Seated on a ship going through a portal, the player saw the ship's own dimension behind the portal (where that part of the ship is not drawn) from the moment their head passed the portal until the ship crossed fully. The view and the crosshair now go through the portal: the player sees, and can use blocks in, the destination as if they had crossed. They stay seated; the ship takes them along when it crosses fully, as before.

## [1.20.4] - 2026-10-13

### Fixed
- The hot air burner's flame is cut at the portal plane while its ship straddles a portal (it showed on the other side), and at the portal in portal views.
- Particles from the part of a ship that has crossed a portal (burner smoke and flames) now appear on the far side, where that part is, instead of behind the portal in the ship's own dimension.
- Particles in another dimension seen through a portal from more than 10 blocks away no longer freeze, stay forever and jitter: they now age and move as usual.

## [1.20.3] - 2026-10-13

### Fixed
- A ship pushed into a portal that the player sees it through (rather than pulled out of one) often paused for a split second when it went through fully. The ship's last movement update could reach the client after the hand-off over Sable's separate movement channel, and the client then could not join the old and new copy of the ship. The last pose is now sent once more on the ordered connection right before the hand-off.

## [1.20.2] - 2026-10-13

### Fixed
- The physics staff's selection box now shows on the part of a ship that has crossed a portal (it was drawn only on the part still in the ship's own dimension).

## [1.20.1] - 2026-10-13

### Fixed
- Physics staff across portals: the staff in the hand no longer turns away when the dragged ship goes through a portal (it leaned towards the ship's position in its own dimension), and the beam no longer flickers or flings at that moment.
- The staff's beam is drawn through the portal up to the ship instead of ending at the portal.
- The staff's hover box shows on ships seen through a portal before they are grabbed.
- Lock icons: ships seen through a portal show theirs; ships hidden behind a portal no longer show theirs through it (they appeared inside the portal's view).

## [1.20.0] - 2026-10-12

### Added
- The Create Aeronautics physics staff works through portals: a ship seen through a portal, or the far half of a ship half through a portal, can be grabbed, dragged, turned and locked. A drag goes on when the player walks through a portal while holding a ship, and when the dragged ship goes through a portal into another dimension (it can be pulled through a portal, or pushed away through one). A ship hidden behind a portal in the player's own world is no longer grabbed through the portal's view. The local player's beam and particles point at the ship where it is seen.

## [1.19.17] - 2026-10-12

### Fixed
- A ship touching a portal's frame (pressed against the obsidian, e.g. with the physics staff) could start "straddling" and be cut at the portal plane with its far half drawn in the other dimension: whether a ship goes through a portal was judged against the opening grown by half a block, which reaches into the frame. It is now judged against the opening itself (the physics clip regions keep their half-block margin).

## [1.19.16] - 2026-10-12

### Fixed
- A ship that had started into a portal and then slid along its plane out of the opening stayed "straddling": cut at the plane, with its far half drawn in the other dimension, while it sat beside the portal (shown up by 1.19.15, which finds large portals from anywhere along them). A session now closes as soon as no block at the plane is inside the opening.

## [1.19.15] - 2026-10-12

### Fixed
- A ship passing through a large portal away from its centre went straight through as if there were no portal (only "a spot" near the middle worked): portals were looked up with vanilla's entity search around the ship, which only finds an entity filed in a nearby chunk section, i.e. one whose centre is near. Portals are now found with IP's lookup for large entities (plus global portals): ship straddling, the one-step crossing, and Create trains through portals.

## [1.19.14] - 2026-10-12

### Changed
- More debug lines in `debug.log` for a sub-level near a portal that is never considered for crossing (too large, no blocks read from its plot, or meeting a duplicate portal entity), after 1.19.13's log showed nothing at all for a sub-level that kept passing through a portal. No behaviour change.

## [1.19.13] - 2026-10-12

### Fixed
- A ship roped to another could be refused entry into a portal for no visible reason (1.19.10's rule): a rope partner anywhere on the far side of the portal's infinite plane, even far off to the side, blocked it. Only a partner behind the doorway itself (within 32 blocks of its opening) counts now.

### Added
- Debug lines in `debug.log` ("[IPStraddle] ship ... at portal ... not crossing: <reason>", at most once a second per ship and portal) for a ship at a portal plane that does not start or continue crossing, with the detector's view.

## [1.19.12] - 2026-10-12

### Fixed
- Ships could vanish from the client for good (until a relog), leaving ghost ropes, when the player went through the portal in the same moment the ships were handed off: Sable's "stop tracking" packets sent when a ship is removed carried no dimension, so a client that had just changed dimension applied them to its new world, where the ships' new copies sat at the same plot coordinates; the hand-off latch then removed the new copies. Those packets are now tagged with their dimension like the rest of Sable's tracking, and the latch only ever holds a ship's copy in the dimension it left.

## [1.19.11] - 2026-10-12

### Fixed
- A balloon roped to its hull could still get separated from it (1.19.10, log of 09:17): it went over the frame of the portal the hull went through, swung back through the edge of the opening into the portal's opposite face, sat there half-cut for over a minute, and the hull crossed alone after 5 s, breaking the ropes. A roped ship no longer enters the opposite face of a doorway a ship it is roped to is crossing, and a ship wholly past the portal plane outside its opening (over the frame) now crosses with the ships roped to it instead of holding them up.

## [1.19.10] - 2026-10-12

### Fixed
- A balloon roped to its hull could jump through a portal it never went through, breaking the ropes (since 1.18.0): having passed the portal plane over the frame, it was pulled back with its edge through the opening's margin, which counted as entering the portal's opposite face; it then waited 5 s for the hull on that face (which used the other face) and crossed alone. A roped ship no longer starts crossing a face while a ship it is roped to is already past that face without having crossed it, and ships roped together count as crossing the same portal when their portal entities lead to the same place the same way (IP may create several for one doorway).

## [1.19.9] - 2026-10-12

### Fixed
- Block entities of a straddling ship (chests, signs, anything Create does not draw through Flywheel) were drawn whole on the side of the portal their block centre was on, so a chest at the plane showed partly on the wrong side. Their geometry is now clipped exactly at the portal plane (on the CPU: they are drawn in a batch after the ship's clip plane is gone), on the ship's side and in the far half.
- A thin sliver of a straddling rope could show in a portal view looking at the cut from behind (1.19.8's seam offset). Ropes, like the ship, draw nothing in a portal view that sees only the dropped side of the cut.

## [1.19.8] - 2026-10-12

### Fixed
- Slight z-fighting where a rope crosses the portal: the rope's end lay exactly in the portal surface. It now ends 1 cm short of it when drawn directly and 1 cm past it inside a portal view, like the ship's own cut.

## [1.19.7] - 2026-10-12

### Fixed
- Pieces of a straddling rope showed on the wrong side of the portal, and a rope crossing it had a gap (seam): the rope's segments were cut square to the rope, so a segment meeting the portal plane at a shallow angle (a balloon's ropes beside a vertical portal) stuck through the plane by much of the rope's thickness on one side and left a gap on the other. A segment crossing the plane now ends exactly in the plane on both sides. The "[IPRopeDiag]" lines are gone.

## [1.19.6] - 2026-10-12

### Changed
- Diagnostic build: a piece of a straddling rope near the portal plane still shows where it should not (1.19.5 changed nothing). Temporary "[IPRopeDiag]" lines log every draw of a rope segment crossing the cut: which pass, which world, its points and the parts kept. No behaviour change.

## [1.19.5] - 2026-10-12

### Fixed
- A rope running past the portal plane beside the portal frame (a balloon's ropes alongside a big portal) was cut on the ship's side and drawn on the far side, where it never went. Rope parts now count as through the portal only when they are past the plane and inside the portal's opening; the ship's side draws everything else.

## [1.19.4] - 2026-10-12

### Fixed
- In the far half of a straddling ship, the ends of every rope (also ropes nowhere near the portal) showed, and rope segments hidden by the cut showed as flat squares: hidden segments were drawn squashed to zero length, and the rope connectors' end pieces were exempt from the cut along with the rope. Hidden segments are no longer drawn, and the end pieces follow the block-centre cut like the ship's other block entities.

## [1.19.3] - 2026-10-12

### Changed
- 1.19.2 verified in game: ropes of straddling ships show correctly on both sides of the portal. The "[IPRopeDiag]" lines are gone.

## [1.19.2] - 2026-10-12

### Fixed
- The far half of a straddling ship's rope was not drawn (1.19.0): the client's copy of a ship plot keeps no list of its chunks (Sable fills it on the server only), so the far-half pass found no rope connectors to draw. The plot's chunks are now read from the client level. The "[IPRopeDiag]" lines stay for one more test.

## [1.19.1] - 2026-10-12

### Changed
- Diagnostic build: the far half of a straddling ship's rope is still not drawn (1.19.0). Temporary "[IPRopeDiag]" lines show whether the far-half pass finds the ship's global block entities and whether the rope renderer is reached there. No behaviour change.

## [1.19.0] - 2026-10-12

### Added
- Ropes of a straddling ship are drawn on both sides of the portal: on the ship's own side up to the portal plane, on the far side from the plane on, mapped through the portal like the ship. Each rope segment is trimmed exactly at the plane. Limitation: the rope follows the sessions of the ship that owns it (the end it was tied from); a rope whose owner is not straddling is drawn whole on its own side.

### Changed
- 1.18.2 verified in game (ropes show after a crossing). The "[IPRopeDiag]" lines are gone.

## [1.18.2] - 2026-10-12

### Fixed
- Ropes (and any other block entity drawn whatever the camera sees, like rope winches) on ships outside the dimension the game started in were invisible (bugs.md open item 4, and ropes after a crossing): Sable compiles every ship on one shared section dispatcher, so their "global" block entities were registered with one dimension's renderer and only drawn when that dimension was drawn, at the wrong place. They now go to the renderer of their own dimension. The "[IPRopeDiag]" lines stay for one more test.

## [1.18.1] - 2026-10-12

### Changed
- Diagnostic build: ropes of a ship that crossed are invisible on the client in the destination (bugs.md open item 4, "rope invisible in the nether", looks like the same thing). Temporary "[IPRopeDiag]" info lines follow a rope from the server's holder to the client's renderer. No behaviour change.

## [1.18.0] - 2026-10-12

### Added
- Ropes survive a crossing when both ends are on ships that cross: a rope with both ends on one ship travels with it (also with the one-step crossing), and ships roped together (a balloon tied to its hull) cross as one group while straddling: a ship that is through first waits in its session for up to 5 s until the others are through the same portal, then all are handed off together with their ropes. Ropes tied to the world, or to a ship that does not cross in time, still break as before.
- Test hook `/ip_aeronautics straddle debug rope <from> <to>` (ties two rope holders) and the headless tests `handoff-ropes-ship.txt`, `handoff-ropes-group.txt`, `handoff-ropes-group-shift.txt`.

## [1.17.6] - 2026-10-12

### Fixed
- Create machinery on a crossing ship stood still for one tick after the move (its kinetic network was rebuilt at the engine's next tick), so a propeller lost 15% of its spin and took about a second to recover. The network is now rebuilt as the ship loads.

### Added
- Test switch `-Dip_aeronautics.debug.handoffTrace=true`: per-tick log of straddling ships and of ships just handed off (pose, velocity, kinetic speeds, propeller spin). Headless test `scripts/server-test/handoff-propeller.txt`.

## [1.17.5] - 2026-10-12

### Fixed
- Every ship vanished for a moment when the player walked through a portal (known since 1.4.x): Sable stopped tracking the ships of the dimension the player left, and the ones still visible through the portal only came back at the next portal-view rescan (up to half a second). Ships the player still sees through the portal now stay on the client and keep moving; ships of the arrival dimension the client already had through the portal are no longer sent again in full.

### Changed
- The hand-off frame trace and the hand-off log lines of 1.17.1-1.17.4 are gone (debug level now).

## [1.17.4] - 2026-10-12

### Fixed
- The ship still hitched at a hand-off (1.17.3's frame trace: a jump of a third of a block back, then a rush forward, or a jump forward): the new copy was started from its own dimension's interpolation clock, which can be several ticks stale. It now starts exactly where the old copy is drawn and plays on the old copy's timeline, which drifts onto its own dimension's clock at a tenth of a tick per tick. The frame trace stays in this build.

## [1.17.3] - 2026-10-12

### Changed
- Diagnostic build for the hand-off hitch: for 1.5 s after each straddle hand-off the client logs every frame (frame time, which copy of the ship is shown and where) as "[IPStraddle] client trace" lines. No behaviour change.

## [1.17.2] - 2026-10-12

### Fixed
- The hand-off stutter was still there when the ship arrived in a dimension the player only saw through a portal: the new copy reached the player up to half a second later (ships seen through portals were picked up every 10 ticks). Players who see the ship's arrival area through a portal now get it at once. The two client copies are paired only by an exact pose match (1.17.1 accepted a near one, a few ticks apart), from whichever side arrives last, and a duplicate first snapshot that made the new copy stand still for a tick is dropped.

## [1.17.1] - 2026-10-12

### Fixed
- A ship froze for a moment when it was handed off to the destination after straddling a portal, then jumped on: the client held the old copy at its last pose until the new copy's meshes were built, and the new copy started without pose history. The new copy now continues the old copy's motion, and the old copy is drawn where the new copy is until it takes over. (Temporary info log lines "[IPStraddle] client: ... hand-off ..." record each hand-off.)

## [1.17.0] - 2026-10-12

### Fixed
- A ship that went fully through a portal while straddling but could not be moved to the destination (taller than the destination's build height, no free plot there, or loading it there failed) was put back in its own dimension behind the portal, at rest. It now stays in its session, physically in the destination, and is pushed back towards the portal plane, so it can always back out. A failed load is not retried for 5 seconds.
- Both crossing paths check the destination's build height and free plots before removing the ship, so a refused move no longer removes and restores it.

## [1.16.2] - 2026-10-12

### Fixed
- A hot-air balloon lost its lift while its ship straddled a portal, so the ship fell (since 1.13.0): the burner finds its envelope with a raycast, and raycasts skipped the part of the ship past the portal plane. Raycasts that start on the ship (burners, steam vents, lasers, sensors) see the whole ship again; only raycasts from outside it (aiming, the physics staff, projectiles) skip that part.

## [1.16.1] - 2026-10-12

### Fixed
- Sneaking along the edge of a straddling ship's far half could stop you when walking at an angle: the edge check ran along the world's axes. It now runs in the far half's frame, like Sable's on the ship's own half.

## [1.16.0] - 2026-10-12

### Added
- The far half of a straddling ship has its blocks' effects on entities in the destination dimension: soul sand and honey slow you and honey stops jumps, cobwebs, berry bushes and powder snow catch you.

### Fixed
- Sneaking on the far half of a straddling ship: the edge check found no ground anywhere there, so a sneaking player could not move. The far half now counts as ground (and edges stop you as on any ship).

## [1.15.1] - 2026-10-12

### Fixed
- Standing on a straddling ship near the portal, your own model (a huge helmet) showed in the portal view: Sable's entity lookup over a ship's plot also returns world entities standing on it, and 1.15.0 drew them as far-half entities. Only entities placed on the ship are drawn now, never the camera entity.
- Entities placed on a ship (Contraption Diagrams, item frames) were missing for players in another dimension (on the far half, or seeing the ship through a portal): they were sent only to players in the ship's own dimension. They now also go to the players the ship is synced to.
- The hit sound of a block broken through a portal played at that block's coordinates as if they were in your own world (inaudible, or somewhere else); it now comes through the portal.

## [1.15.0] - 2026-10-11

### Added
- Entities placed on a straddling ship (Contraption Diagrams, item frames, paintings) are drawn on its far half in the destination dimension.
- Block-breaking cracks on the far half of a straddling ship show in the destination dimension.

### Fixed
- In a straddling ship's own dimension, a crack could show on a block past the portal plane (where the ship is not drawn).

## [1.14.1] - 2026-10-11

### Fixed
- The far half of Create parts (1.14.0) was never drawn: Flywheel serves a level's visuals only while it is the current client level, so the ship's dimension is made current for the far-half draw (as IP's portal views do).

## [1.14.0] - 2026-10-11

### Added
- The far half of a straddling ship's Create parts (cogs, shafts, propellers and other Flywheel-drawn parts) is drawn in the destination dimension, cut at the portal plane like the ship's blocks: the ship's own dimension's Flywheel engine draws them a second time with the camera mapped back through the portal, keeping only that ship's parts.

## [1.13.3] - 2026-10-11

### Removed
- Physics Mod's ocean handling, inherited from the fork. Physics Mod turns its ocean, snow and vine physics off by itself next to Immersive Portals; only skipping its draw in portal views stays.
- The temporary block-event log lines of 1.13.2 (chest lids and sounds through portals confirmed in game).

## [1.13.2] - 2026-10-11

### Fixed
- Sounds, block events (chest lids) and level events did not reach players seeing them from another dimension: Sable replaces Immersive Portals' cross-portal broadcast with a same-dimension one. They are sent again to players who see the chunk through a portal, and for ships to the players the ship is synced to (through a portal, or near its far half). A chest closed after walking through a portal no longer stays open on the other side.
- Sounds on a Sable ship in another dimension were dropped instead of coming through the portal (their position is in plot coordinates).

### Changed
- Temporary info-level log lines for block events sent to and received from other dimensions (diagnostics).

## [1.13.1] - 2026-10-11

### Fixed
- A chest on a ship did not open its lid for players seeing it from another dimension (on the far half of a straddling ship, or through a portal): block events on ships only reached players in the ship's own dimension. (The chest tested in game was in terrain, which only 1.13.2 covers.)

## [1.13.0] - 2026-10-11

### Added
- Blocks on the far half of a straddling ship can be targeted, broken, placed against and used (chests and other menus included) from the destination dimension, and from the ship's own dimension through the portal. The target outline is drawn where the far half is.
- Blocks of a Sable ship seen through a portal can be targeted and edited, like terrain behind a portal.

### Fixed
- In a straddling ship's own dimension, the part of the ship past the portal plane could still be targeted (the physics staff highlighted blocks behind the portal) and was hit by projectiles. Raycasts now skip it; a block crossing the plane is hit only on its visible part.
- Aiming at a ship block with a portal behind it targeted the portal instead: the ship hit was measured in plot coordinates.
- Block changes on a ship seen through a portal (or on a far half) did not reach the viewer until they walked over.
- Breaking a ship block through a portal could write the wrong block state into the client's copy of the ship.

## [1.12.1] - 2026-10-11

### Fixed
- Entities placed on a straddling ship (Simulated's Contraption Diagram, item frames, paintings) showed behind the portal; they are now hidden past the portal plane like the ship's blocks (not drawn on the far half yet).

## [1.12.0] - 2026-10-11

### Changed
- Ships straddling portals is on by default (with our physics backend active): a ship entering a portal stays one rigid body across both dimensions, drawn and solid on both sides, and moves to the other dimension once wholly through. `/ip_aeronautics straddle off` or `-Dip_aeronautics.straddle=false` brings back the one-step crossing.

### Fixed
- Walking into a portal from one face while a ship sits in its opening on the other side of the plane (straddling from the other face, or parked there): the ship blocked the entity. Like IP does for blocks, ship blocks beyond a portal the entity touches, inside its opening, no longer collide.

## [1.11.2] - 2026-10-11

### Fixed
- A straddling ship could vanish for a few seconds (and let you fall) while you stood on its far half: it lives in the other dimension and was only synced to you while its chunks were visible through a portal. Players within 96 blocks of a far half now always get the ship.
- The invisible slivers at the portal frame were still there on both sides: a resting ship is always slightly tilted, so the exact trim never applied. Collision is now trimmed along the plane's main axis whenever it is within 45 degrees of one.
- Footstep sounds on the far half are the far half's blocks.

## [1.11.1] - 2026-10-11

### Fixed
- Invisible but solid slivers of a straddling ship behind its portal: collision of a block crossing the portal plane now ends at the plane on both sides (exact when the plane is aligned with the ship's blocks, the usual case).
- Landing on and walking over the far half of a straddling ship use that block's effects (a hot-air envelope bounces, fall damage and friction follow the block).
- Fences, walls and panes on the far half collide with their connected shapes.

## [1.11.0] - 2026-10-11

### Added
- Step 5 of seamless ship crossings, second part: the far half of a straddling ship is solid in the destination dimension. Players, mobs and items collide with it and can stand on it (they are not carried along across ticks while it moves, unlike on a ship in their own dimension). A player standing there is not kicked for floating.
- Walking across the portal plane on a straddling ship: while an entity touches a portal, Sable's ship collision now runs too (IP's portal collision used to replace it entirely, so the player fell through the ship at the portal); IP's portal-aware collision answers the terrain part.

### Fixed
- A ship block that only touches the portal plane counted as crossing it (the far half was one block too tall to stand on).

## [1.10.0] - 2026-10-11

### Changed
- Step 5 of seamless ship crossings, first part: in a straddling ship's own dimension, the part of the ship that is through the portal (and no longer drawn there) is no longer solid for players, mobs and items. Blocks crossing the plane stay solid. (Standing on the far half in the destination dimension comes next.)

## [1.9.4] - 2026-10-11

### Fixed
- The blink when a straddling ship finishes crossing: the client keeps the ship's old copy (and its far half in the destination) until the copy that arrives in the destination dimension has compiled meshes, at most 3 s (`IPStraddleHandoffLatch`, deferring Sable's stop-tracking).

## [1.9.3] - 2026-10-11

### Added
- Flywheel-drawn parts of a straddling ship (Create cogs, shafts, propellers) are cut at the portal plane like its blocks, on both Flywheel backends (`IPFlywheelStraddleClip`: a per-environment plane, in a shader storage buffer at binding 8 on the indirect backend, a uniform on the instancing backend). Their far half is not drawn yet.

## [1.9.2] - 2026-10-11

### Fixed
- Thin lines tracing a straddling ship's block edges in portal views (the "line at the cut"): looking through a portal from the side where the ship's half faces away, IP keeps what is beyond the portal plane (from 1 cm before it) while the ship's cut keeps its half before that plane, so a 1 cm slice of the ship at the plane survived. The ship is now not drawn at all in such a view.

## [1.9.1] - 2026-10-11

### Fixed
- A thin flickering line where a straddling ship meets the portal: the ship was cut exactly on the portal plane, at the depth of the portal's own surface. It is now cut 1 cm before the plane when seen directly and 1 cm past it when seen through a portal, so both views still meet.
- Block entities that draw at once with their own shader (Aeronautics' burner flame) on a ship right in front of a destination portal showed inside that portal's view, as if behind the portal: in portal views, ship block entities on the clipped side of the portal plane are now skipped.
- A ship resting at a portal plane (or opened one tick early and then slowed down) opened and closed its straddle session every few ticks; a session now stays open while the ship is within 0.5 blocks of the plane, and at least 10 ticks.

## [1.9.0] - 2026-10-11

### Added
- Step 4 of seamless ship crossings, first part (docs/completed/seamless-ships.md): with straddling on, a ship halfway through a portal is drawn cut at the portal plane on its own side, and its far half is drawn in the destination dimension (when you are there, and when you look into the destination through a portal), cut at the destination plane. Clients learn about straddling ships from the server (`IPStraddleSync`). Ship block entities are shown on the side their centre is on. Not yet covered: Flywheel-drawn parts (cogs, shafts), entities on the ship, single-block ships, Iris shader packs (the ship is then not cut, and the far half is not drawn), and the blink at hand-off.

### Changed
- IP's vanilla terrain shaders (`rendertype_solid`/`cutout`/`cutout_mipped`/`translucent`) get a second clip distance, `gl_ClipDistance[1]` with uniform `ipa_ShipClipEquation`, enabled only while a straddling ship is drawn.

## [1.8.0] - 2026-10-11

### Added
- Step 3 of seamless ship crossings (docs/completed/seamless-ships.md), physics only and **off by default**: with `/ip_aeronautics straddle on` (operators; or `-Dip_aeronautics.straddle=true`) and our physics backend active, a ship entering a portal is no longer moved at first touch. While it straddles the portal, the part through it collides with the destination world's terrain and ships (an image of the ship in the destination's physics chart, same rigid body) and no longer with what is behind the portal in its own world; the ship can rest half through, push and be pushed by ships on the far side, and back out. Once wholly through it is moved to the destination dimension at exactly its mapped position, keeping its velocity. Rotated portals work; scaled portals are still not crossed by ships.
- `/ip_aeronautics straddle debug on` draws the part on the far side as an orange particle outline. Nothing else is drawn there yet (step 4), and players standing on a straddling ship fall through the far half (step 5).

### Changed
- Sable's physics terrain tickets also cover the destination area of straddling ships (`MixinSablePhysicsTickets_StraddleImages`); no effect while nothing straddles.

## [1.7.0] - 2026-10-11

### Changed
- Step 2 of seamless ship crossings (docs/completed/seamless-ships.md): with our physics backend, all dimensions now share one physics world, each dimension a separate "chart" in it, and are stepped together once per substep. Ships in different dimensions never touch each other or each other's terrain. No straddling yet; ships cross portals as before. The native library is now IPSable 0.5.0's version of Sable's crate and of the Rapier engine (Apache-2.0 changes on Sable's PolyForm Shield code; credited in NOTICE), rebased on Sable 2.0.6.
- Every dimension's whole Sable sub-level tick now runs at the start of the first dimension's tick in each server tick, phase by phase (`IPFusedPhysicsStep`), so the nether's and the End's ship updates happen slightly earlier in the tick than before, in the same order as before within each dimension. Players and entities standing on ships keep being carried in every dimension (IPSable's fused step lost that for all but the overworld). Unchanged without our backend.
- Block collision callbacks (fragile blocks, bells, beehives, explosives) run against the dimension of the contact (`LevelBoundCollisionCallback`); with one shared step they would otherwise read another dimension's blocks, and ships fell through leaves.

### Fixed
- (Our backend only, from IPSable) A data race in Sable's collision report buffer, filled from Rapier's parallel solver threads, that could kill the game under heavy contact load; native panics no longer abort the JVM.

## [1.6.0] - 2026-10-11

### Added
- Our own copy of Sable's Rapier physics backend (step 1 of seamless ship crossings, docs/completed/seamless-ships.md). It is Sable 2.0.6's backend unchanged except for its name: Java package `ipaeronautics.rapier`, native library `ipa_rapier`, built for Linux, macOS and Windows on x86_64 and arm64 (`scripts/build-natives.sh`) and extracted to `.ip_aeronautics/natives/` in the game directory. Physics should behave exactly as before. Every level uses it when its library loads and Sable is 2.0.6; otherwise, or with `-Dip_aeronautics.stockPhysics=true`, every level uses Sable's own pipeline as before. Logged per dimension as "[IPPhysics] <dimension>: using the ipa_rapier pipeline" (or "Sable's stock Rapier pipeline", with the reason logged once).
- This code is copied from Sable and stays under Sable's licence (PolyForm Shield 1.0.0), shipped in the jar as `META-INF/LICENSE-SABLE.md`; NOTICE and docs/licensing.md describe the split.

## [1.5.1] - 2026-10-11

### Added
- Groundwork for ships straddling portals (docs/completed/seamless-ships.md): the mod registers its own Sable physics provider, which for now hands every dimension to Sable's stock Rapier pipeline, so physics is unchanged. Logged as "[IPPhysics] <dimension>: using Sable's stock Rapier pipeline". This checks that Sable picks up a provider from this mod under NeoForge.

## [1.5.0] - 2026-10-11

### Added
- Hot-air balloons keep their lifting gas through a portal. Removing a ship from its level made Create Aeronautics drop its balloons, and the burners built empty ones in the destination: the ship arrived without lift and sank for the 10-25 s the gas took to refill. The balloons of a crossing ship are now snapshotted before it leaves and parked in the destination level's balloon map (Aeronautics' own "unloaded balloon" path, shifted with the plot) right after it loads, so its burners take the gas over on their first tick (`IPShipBalloonCompat`). Parked balloons an earlier occupant left in the arrival plot are discarded first. A crossing that fails and restores the ship to its own level restores its balloons the same way. Logged as "aeronautics: carried N balloon(s) ... (X m3 of lifting gas)".

## [1.4.23] - 2026-10-11

### Fixed
- A ship that had crossed a portal weighed twice as much until the next relog: a hot-air balloon that had carried it before could no longer lift it, whatever the burner output (other propulsion got sluggish). After loading the ship in the destination, every block was replayed into Sable's physics as newly placed (`handleBlockChange(air -> state)`), inherited from Immersive Aeronautics, but Sable 2.0.6's `ServerLevelPlot.load` already does that for the mass tracker and the physics pipeline, so the mass and inertia were counted twice. The replay is gone; the arrival log now reports the ship's mass ("loaded ship ... mass") instead of "rebuilt physics".

## [1.4.22] - 2026-10-10

### Fixed
- After leaving a singleplayer world and loading it again, a ship that had crossed a portal in the previous session could not cross again for as long as that session had lasted: it drove through the frame and stayed in its dimension. The per-ship crossing state (5 s cooldown, "just arrived" suppression, previous hull centre) is static and keyed by server tick counts, which restart at 0 with every server, while a singleplayer game keeps one JVM across world loads; a ship last moved at tick 18000 sat in its cooldown until the new server reached tick 18100. Ships that were not loaded at the new server's first scan had their state pruned, which is why it hit only some ships. All per-server state of the ship integration (crossing state, through-portal sync, pending re-seats/remounts, ragdoll suppression) is now reset when a new server starts, and the teleport journal is replayed on every server start instead of once per game. A crossing skipped for the cooldown is logged at debug.
- A player seated on a ship (Create seat/cushion) was teleported through the portal by Immersive Portals itself when their head reached the portal plane before the ship crossed (or when the ship did not cross, as above). The teleport moves the rider's vehicle along, so the seat was pulled out of the ship's plot and left standing in the portal on the far side with the player stuck on it until a relog. A rider whose vehicle is in Sable plot space is now never teleported by Immersive Portals (client and server); the ship crossing carries them (`IPSableCompatHelper.isRidingOnShip`).

## [1.4.21] - 2026-10-10

### Fixed
- An object that had crossed a portal and come back often could not be sent through again ("no room for ship ... crossing refused" on every try; 1.4.19/1.4.20). The arrival search tested the object's world AABB against the terrain, and every crossing stops the object and drops it, so it lands tilted: the AABB of a tilted object is the rotated box of its whole plot and several times its real size (20 x 22 x 13 blocks for the 304-block test object), which fits nowhere in a nether cave. A relog did not reset anything; the next push simply came from a different side. The search now tests the cells the object's blocks really cover (`IPShipArrivalClearance.Shape`, read from the plot), and other ships are painted into the terrain grid instead of being tested box against box. Portals are still kept clear of the AABB, the box the portal scan itself uses. Replayed offline against the test world's nether terrain with random tilts and entry points: refusals dropped from 27 of 80 to 2 of 80.
- A refused crossing logs the hull size and the number of cells tested.

## [1.4.20] - 2026-10-09

### Fixed
- A ship that crossed a portal and came straight back left a frozen, unmovable copy in the dimension it had visited (gone after a relog). The client's copy there came from the through-portal sync, which stops a ship only at its next rescan (every 10 ticks); the player walked into that dimension 0.3 s after the ship left, and on a dimension change the sync forgets the arrival dimension's ships without a stop packet (Sable's own tracking takes them over, but this ship was no longer there). Portal viewers now get the stop packet as soon as a ship leaves a dimension (`IPShipThroughPortalSync.onShipLeftDimension`), and the dimension-change path sends it for ships that are no longer in the arrival dimension.
- A ship placed high above the ground by 1.4.19's arrival search fell, landed next to the portal it came through and was carried back after the 5 s cooldown: a ship touching a portal while "standing still" (which includes falling past it or tumbling beside it) counted as entering it. A ship must now be moving into the portal (at least 0.25 m/s along its normal; was 0.05 m/s, plus the standing-still case), and the arrival search adds 0.5 per block the hull would fall, so positions resting on the ground win over ones in mid-air. A ship pushed to a stop halfway through a portal now stays there until it is pushed further.

## [1.4.19] - 2026-10-09

### Fixed
- A ship sent through a portal whose far side runs into terrain (e.g. a nether portal generated half inside netherrack) was placed inside the blocks, sank through the floor and was deleted by Sable ("extreme Y coordinate range"). `IPShipArrivalClearance` only searched upwards (to y=128 in the nether, which rarely has room for a whole hull) and fell back to the mirrored position. It now searches the nearest position where the hull overlaps no block with a collision shape, no lava, no portal and no other ship: outwards from the portal, up, sideways and a little down (16 sideways, 24 up, 8 down), never back towards the portal or above the logical height. With no room in reach the crossing is refused: the ship stays in its dimension and is pushed back out of the portal (retried after the usual 5 s cooldown).
- The stuck-ship shove (a ship sitting in a portal mouth for 10 s) moved ships along the portal normal without looking, here 10 blocks deeper into the netherrack. It now uses the same search and leaves the ship in place (still suppressed, retried every 10 s) when there is no room. The shove is logged at info level.

## [1.4.18] - 2026-10-09

### Changed
- Clean-up after the ship-rendering fixes (1.4.10-1.4.17, verified in-game):
  - The neighbour-check fix lives on its own as `MixinRenderSection_ShipNeighbors` (one debug line on first use); the per-section counters and dirty-caller stack traces (`MixinRenderSection_ShipStats`, `IPShipSectionStats`, `IPSectionRenderDispatcherAccessor`) are gone.
  - 1.4.14's synchronous first compile of ship sections (`MixinSableChunkedRenderData_FirstCompileSync`) is removed: it rested on a theory the next log disproved, and with the neighbour check fixed Sable's normal near-sync/far-background compiles finish.
  - Render watchdog: no `resize()` re-queue on refresh (never needed: no dirty-but-unqueued sections were ever seen), no queue/stat diagnostics, no per-finalize log line; it logs a ship's non-air and uncompiled section counts when they change and heals on stalled uncompiled sections only. The finalize mixin is back to its 1.4.12 form.
- bugs.md restructured: open reports first, verified and unverified fixes in separate tables, the ship-rendering history condensed to its causes, and the theories ruled out along the way.

## [1.4.17] - 2026-10-09

### Fixed
- Ropes vanished after walking back into their dimension (1.4.16; they returned once the ship moved). The ship's full sync on arrival re-creates its block entities on the client, and a rope's client strand lives on its holder's block entity; since 1.4.16 a portal viewer who walks through stays counted as tracking the rope, so it was never re-sent. After every full sync of a ship to a player (Sable's own or the through-portal one) the ropes attached to that ship forget the player and re-send their strand (`MixinSableFullSync_RopeResend`).

## [1.4.16] - 2026-10-09

### Fixed
- Create Aeronautics ropes were missing from portal views (they appeared once the player crossed). A rope holder sends its strand only to players tracking its chunk in its own dimension, untagged, and the client looks the holder up in the player's own level. Players viewing the holder through a portal (the players a ship is synced to through a portal, or IP's chunk viewers for holders in the world) now get the strand, dimension-tagged (`MixinRopeStrandHolder_PortalViewers`, `IPRopePortalSync`), and the client applies rope packets to the redirected world (`MixinRopePackets_RedirectedLevel`). Tracked rope viewers are looked up server-wide so portal viewers are not re-sent the strand every tick.

### Notes
- 1.4.15 verified: ships no longer lose parts. Its log showed why the neighbour check failed: empty chunks of a ship's plot do not exist on the client and the chunk lookup returns null for them.

## [1.4.15] - 2026-10-09

### Fixed
- Ship sections never finished compiling (parts of ships invisible on first load and through portals; 1.4.14 logs: e.g. 4 compile tasks and 0 completed, or about 100 tasks per second and 0 completed). Vanilla's compile task cancels itself when `hasAllNeighbors()` fails (camera over 24 blocks away and a horizontally adjacent chunk missing in the dispatcher's level), and `cancel()` marks the section dirty again: background compiles re-queued themselves every frame, synchronous ones failed silently inside Sable's compile loop. The check now passes for ship sections (`MixinRenderSection_ShipStats`); missing neighbouring plot chunks only snapshot as empty.

### Added
- The first neighbour-check failure every 10 s is logged with the dispatcher's level, `Minecraft.level` and which neighbouring chunk lookups failed; the per-second ship lines include the count. The dirty-caller stack traces from 1.4.14 now reach debug.log (the logger was named after the vanilla class it is merged into).

## [1.4.14] - 2026-10-09

### Fixed
- Parts of ships stayed invisible (on first load, and for ships seen through a portal). The 1.4.13 diagnostics showed those sections uncompiled for many seconds while being re-queued for compiling every second: far ship sections compile in the background, each compile cancels the previous unfinished one, and sections marked dirty again every few frames (block-entity or light updates of running machinery) never finished a compile. A ship section's first mesh is now built synchronously (`MixinSableChunkedRenderData_FirstCompileSync`); later rebuilds stay in the background.
- Reverted 1.4.13's render-data recreation on a ship's first finalize: it fixed nothing and rope connectors went invisible with it.

### Added
- Diagnostics: per ship section, clean-to-dirty marks, compile tasks and completed compiles are counted (`MixinRenderSection_ShipStats`); while a ship has uncompiled sections the watchdog logs the counts for the last second, and the caller that marks a ship section dirty is logged at most every 10 s.

## [1.4.13] - 2026-10-09

### Fixed
- Freshly tracked ships could lose parts (a balloon, half a hull) until a portal round trip: on first load, and for overworld ships re-sent while the player is in the nether (most of a ship invisible through the portal). The fork's finalize handler marked the existing render data dirty for every ship instead of only for re-sent ones, so a new ship kept the render data made at start-tracking time, before its chunks arrived. A ship's first finalize now recreates its render data as Sable does.
- The render watchdog's rebuilds could not unstick a section that was dirty but not queued for compiling (Sable queues a section only when it turns from clean to dirty); rebuilds now re-queue every dirty section through Sable's `resize()`.
- The watchdog's section diagnostics (1.4.12) never saw any blocks (its plot-chunk lookup found nothing), so its stuck-section heal never ran either. It now reads the render data's own sections and the level's chunks: per ship, non-air sections, uncompiled, compiled without geometry, queued for compile, dirty but not queued, and plot chunks loaded. Each finalize is logged (fresh or re-sent, plot chunks loaded).

## [1.4.12] - 2026-10-09

### Fixed
- Block entities of a ship seen through a portal "ghosted" into the player's own dimension and were missing from the portal view. Sable's ship block-entity renderer keeps the `RenderBuffers` its level renderer was built with (the main set), while IP's portal pass draws into a secondary set and flushes only that one; the ship's block entities were therefore drawn later by the main view. The renderer now uses the current `Minecraft.renderBuffers()` (`MixinSableBlockEntityRenderer_CurrentBuffers`).
- Sable's shared section dispatcher compiled nearby ship sections into the buffers of whichever `RenderBuffers` existed when it was created, which the main buffer source also writes into (or a secondary set IP lends to portal passes). Its fixed buffers now follow the current pass before each compile, as IP already does for its own dispatchers.
- The render watchdog's scheduled rebuilds were keyed by plot coordinate only, so with ships in two dimensions every container counted them down and one dimension's finalize could rebuild another dimension's ship at the same plot. Entries now carry their level (held weakly).

### Added
- Diagnostics: the watchdog logs, per ship and whenever they change, how many of its non-air sections are uncompiled and how many are compiled without any geometry; the latter now also count as stuck for its bounded heal (a ship drawn as block entities only).

## [1.4.11] - 2026-10-09

### Fixed
- After the player changed dimension, every ship's blocks became invisible (only its block entities floated in place), in the dimension the ship was in and after moving it across. 1.4.10's premise was wrong: Sodium has one `SodiumWorldRenderer` per `LevelRenderer`, and IP keeps one level renderer per dimension, so each one's `level` was already right. 1.4.10's restore call ran while the portal-side renderer was still current and pointed that dimension's Sodium renderer at the player's world after every portal pass. Once the player crossed into that dimension, Sable's Sodium hooks compiled and drew the ships of the dimension just left. The level switch is removed (`MyGameRenderer`, `SodiumInterface`, `IESodiumWorldRenderer` back to 1.4.9).
- Removed 1.4.10's ship-level neighbour check (`MixinRenderSection_ShipNeighbors`, `IPShipSectionOwner`): Sable's client chunk cache returns a chunk (empty if absent) for any coordinate inside plot bounds in every level, so the vanilla check already always passes for ship sections.
- Create Aeronautics' hot-air balloon texture "ghosted" into the other dimension. The overlay is drawn for `Minecraft.level`'s balloons into its own framebuffer and composited with a full-screen blit that only depth-tests against the main buffer, so in a portal pass it painted the portal-side dimension's balloons over the player's own view, outside the portal frame. The effect is now skipped in portal passes (`MixinAeronauticsBalloonEffect_PortalPassSkip`); balloons seen through a portal show their blocks without the heat overlay.

## [1.4.10] - 2026-10-09

### Fixed
- With Sodium, ships of the dimension seen through a portal were not drawn in the portal view (only their block entities and a few sections), while the player's own dimension's ships were drawn into that view as "ghosts", and ship parts stayed missing after crossings. Sodium has a single world renderer whose `level` field IP never switched during a portal pass (it only swaps the section manager), and Sable's Sodium hooks compile and draw the ships of whatever level that field names. The field now follows the pass (`SodiumInterface.setWorldRendererLevel`, called from `MyGameRenderer` around the pass).
- Sections of a ship in a non-current dimension could never finish compiling: the compile task checks neighbouring chunks on a background thread through the shared section dispatcher's level (and Sable's distance override through `Minecraft.level`), both the wrong level for such a ship, and 1.4.9's per-compile level switch made the outcome flip between ships. Ship sections are now tagged with their ship and answer the neighbour check from the ship's own level (`MixinRenderSection_ShipNeighbors`).
- One-shot debug lines record when the Iris deferred-flush clipping and the Flywheel clip plane engage, so a log can show whether they ran.

## [1.4.9] - 2026-10-09

### Fixed
- Entities and block entities (chests, beds, heads on ships, mobs) still leaked into portal views after 1.4.8. With Iris installed, shader pack or not, Iris's batched entity rendering collects every entity and block entity draw and flushes them in one go after the translucent terrain layer, i.e. after IP has closed its entity clipping window, so the deferred draws ran with the clip plane off. The flush is now wrapped so the plane and the clip uniform are active for its duration in portal passes (`MixinIrisBufferedSource_PortalClip`, `IPIrisBatchClip`).
- Parts of ships went invisible after a crossing, and ships showed the blocks of another dimension's ship at the same plot coordinate. Sable's Sodium-path render dispatcher is a singleton with one vanilla section dispatcher whose level is set only when a ship's render data is created; with IP keeping several client levels alive, a ship's sections were compiled from whichever level was set last: empty if that level has no chunks at the plot (sections never complete), or the other dimension's ship if it does. The dispatcher is now pointed at the ship's own level right before every compile (`MixinSableChunkedRenderData_DispatcherLevel`).

## [1.4.8] - 2026-10-09

### Fixed
- Ships vanished client-side after a crossing (invisible and not interactable, back after another crossing, still visible through a portal from the other side), showed blocks of other ships "attached", and every dimension change logged Sable's "tracking removal packet for unknown sub-level". Sable's tracking tick sends a StopTracking packet to a player who just left the level (and full syncs, bounds and movement updates to players in it); the fork exempts Sable payloads from IP's dimension-redirect wrapper, so the client applied that StopTracking to its *current* dimension and removed whatever ship sat at the same plot coordinate there (plot (0,0) exists in every dimension), and other-dimension chunk data could land on the wrong ship the same way. Sable's tracking tick now runs under IP's force-redirect for its level, so everything it sends is tagged with the right dimension (`MixinSableTrackingSystem_DimensionAwareSend`); the client applies packets for a non-current level through the existing portal-side handler.
- Chests, beds, skulls and other block entities (and entities) on ships, and in general, leaked into portal views from behind the destination portal. IP only feeds its clip plane to the vanilla entity shaders when Iris is *absent*; with Iris installed but no shader pack active the vanilla shaders are still in use and need the plane. The check is now "no shader pack active" (`MixinRenderSystem_Clipping`).

## [1.4.7] - 2026-10-09

### Fixed
- Crash ("Sable ticking physics" / "Constraint validation failed ... the second body of this constraint is not a sub-level") when a ship with a Create Aeronautics rope attached to it (e.g. two sub-levels roped together, or a ship moored to the ground) went through a portal. The rope strand stayed behind in the source dimension with an anchor still pointing at the departed ship; Aeronautics' loaded-check only looks at the first sub-level anchor, so the strand was ticked and re-attached its constraint to a body that was no longer a sub-level. Ropes attached to a crossing ship (either end) are now broken through Aeronautics' own rope-destroy routine before the ship leaves (coupling item drops at the rope's midpoint, usual break sound/particles); if the owning anchor block cannot be reached, the strand is detached and removed directly (`IPShipRopeCompat`). Ropes do not follow ships through portals yet.

## [1.4.6] - 2026-10-09

### Fixed
- A Create train (or any Flywheel-rendered contraption) of the destination dimension that stood between the viewer and the destination portal showed up inside the portal view, e.g. a nether train seen from the overworld when looking into the portal from the side that puts the train behind the nether portal. Same cause as the 1.4.5 LOD leak: the fork lets Flywheel draw inside portal views, but Flywheel's vertex programs never received IP's front clipping plane. Every Flywheel vertex program is now patched with a `gl_ClipDistance[0]` line as it is compiled (`MixinFlywheelCompilation_PortalClipSource`), the plane is set whenever a Flywheel program is bound in a portal pass (`MixinFlywheelGlProgram_PortalClipPlane`), and the clip plane is enabled around Flywheel's draw if IP had not already enabled it (`MixinFlywheelRenderDispatcher_PortalClipState`, `IPFlywheelPortalClip`). Covers the instancing and indirect backends; the vanilla fallback path (Flywheel backend off) is not covered.

## [1.4.5] - 2026-10-09

### Fixed
- With Distant Horizons, terrain of the destination dimension that lies *between* the viewer and the destination portal showed up inside the portal view (blocks "from the other side" where the portal should have shown distant terrain or sky; worse the farther the viewer stood from the portal, and only in spots where no vanilla chunk is loaded on the far side). IP clips everything drawn in a portal pass against the destination portal's plane by patching a `gl_ClipDistance` line into the vanilla, Sodium and Veil vertex shaders, but DH draws its LODs and clouds with its own shader programs, which never received that plane. DH's terrain and generic-object vertex shaders are now patched the same way as they are loaded (`MixinDhGlShader_PortalClipSource`), and the active plane is fed to them per pass (`MixinDhTerrainShader_PortalClipPlane`, `MixinDhGenericObjectShader_PortalClipPlane`, `IPDhPortalClip`). Not covered: an Iris shader pack, where Iris compiles DH's terrain program itself.

### Changed
- `NOTICE` no longer refers to repository paths (the source is not published; only the jar is).

## [1.4.4] - 2026-10-09

### Added
- Apache-2.0 compliance pass (`docs/licensing.md`): `NOTICE` file crediting Immersive Portals for NeoForge (iPortalTeam) and Immersive Aeronautics (ItsSoBread); `LICENSE` and `NOTICE` are now inside every jar under `META-INF`; every Java source file carries an origin header (modified upstream file, file rebuilt from the fork, or new in this project; `scripts/stamp-headers.py`); `authors` and `credits` in the mod metadata name all three projects.
- `docs/completed/seamless-ships.md`: design notes for a future "ship half in each dimension" crossing.

## [1.4.3] - 2026-10-09

### Changed
- The five upstream mixin configs (`imm_ptl`, `imm_ptl_compat`, `imm_ptl_fabric`, `imm_ptl_peripheral`, `q_misc_util`) are back to `defaultRequire: 1` as upstream ships them, so an injection that fails to apply stops the game with a clear Mixin error instead of silently leaving a feature broken. The fork had lowered them to 0. Every method and field reference in those configs was checked against the pack's Minecraft, NeoForge, Sodium 0.8.13, Iris and Flywheel jars first (`scripts/mixincheck.py`). The compat configs for optional mods keep `defaultRequire: 0`.

## [1.4.2] - 2026-10-09

### Fixed
- Riding through a portal (1.4.0 seamless crossing) left a ghost copy of the carriage at the portal in the destination world: purple glass in the seat, one controls block dead, the train shown at the portal while really further down the track, a stuck half that could not be interacted with. Create dismounts the rider server-side, but vanilla only tells the client on the next tracker tick, after IP's cross-dimension position packet; IP's client-side dimension change then carried the carriage the client still believed it rode into the new world, where it collided with the far-side copy (Create's copies share one UUID; log line "Duplicate entity UUID") and drove the shared carriage state with frozen sync data. The server now sends the dismount for the carriage the rider left before the teleport, and the client refuses to carry a Create carriage through a forced dimension change.

## [1.4.1] - 2026-10-09

### Changed
- Reverted the 1.4.0 seam change: carriages crossing a portal again show Create's purple glass slice and portal particles at the cut. Rendering the seam block with its real state left up to one block of it protruding behind the portal plane, visible from behind or beside the frame. Cutting it exactly at the plane would need a clip plane in both render paths, and Flywheel's instanced renderer (the active one with Create 6) has none.

## [1.4.0] - 2026-10-09

### Added
- Create carriages crossing a portal no longer show purple stained glass and portal particles at the cut between their two dimension halves. Each half now renders its seam block with its real state; IP clips the portal view exactly at the plane and the other half is the same geometry, so the one-block overlap is invisible (`MixinCarriageContraption_NoSeamGlass`).
- Riding a train through a portal uses IP's seamless player teleport instead of the vanilla dimension change, so there is no loading screen (`MixinCarriageDimensionalEntity_SeamlessRiderTeleport`, `IPCreateRiderTeleport`; vanilla teleport remains the fallback).

## [1.3.5] - 2026-10-09

### Fixed
- A train standing in a portal showed its hidden portal seam flipping from one side of the portal to the other and its wheels animating too fast. Create keeps one carriage entity per dimension the carriage spans, all bound to the same client-side carriage; with IP ticking the portal-side world, both copies applied the server's position sync to the shared travelling points every tick, overshooting the snapshot and correcting back on the next sync. Only one copy per carriage now drives the sync, anchors and wheel angles each client tick (`IPCreateClientCarriageDriver`, `MixinCarriageContraptionEntity_OneClientDriver`); the other copies align to the shared anchors.

## [1.3.4] - 2026-10-09

### Fixed
- A Create train sent through a portal while nobody rode it vanished on the far side until the client reconnected. NeoForge delivers a contraption entity's spawn data in a separate payload and looks the entity up in the player's own level; for an entity IP had added to a portal-side level the lookup found nothing, the carriage never got its contraption, threw on its first tick and discarded itself client-side. The payload is now resolved in the level IP has switched to (`MixinNeoForgeClientPayloadHandler_RedirectedSpawnData`). This applies to every entity with NeoForge spawn data seen through a portal, not only trains.
- Crash on world load, "Cannot read field horizontalCollision because sable$collisionInfo is null", when an entity (here a train passenger re-seated at load) was loaded next to a portal. IP's wrap of `Entity.collide` answers portal collisions itself and never called the wrapped method, so Sable's redirect of it did not run and Sable's later hooks in the same move read a null (first move) or stale (later moves) collision state. IP now records its collision outcome in Sable's state whenever it bypasses the call (`IPSableCollisionBridge`).

### Changed
- The client-side train-rider teleport suppression logs once per crossing instead of on every teleport check.

## [1.3.3] - 2026-10-09

### Fixed
- DH clouds seen from the nether into the overworld were black by day. In singleplayer a DH level answers "which client level am I?" with the player's actual level, so the overworld's cloud handler took its cloud colour from the nether, whose fixed midnight gives the night colour. A DH level now answers with the client level of its own dimension whenever IP holds one.
- The portal-pass frame-index guard for DH's anti-aliasing pattern (1.3.2) shadowed a method inherited from a superclass, which can make Mixin skip the whole injection; it now calls the method directly. If the jitter with anti-aliasing on persisted in 1.3.2, this is why.

## [1.3.2] - 2026-10-09

### Fixed
- Residual LOD jitter with a portal on screen (outer world and inside the portal). With DH anti-aliasing on, DH's terrain shader advances an 8-frame index on every uniform fill and uses it for the anti-aliasing sub-pixel pattern; a portal pass advanced it a second time per frame and drew the pattern without a resolve. Portal passes now undo the advance and draw without the pattern.
- The portal world's LODs bled into the outer world at the border between vanilla terrain and LODs (with DH anti-aliasing off). DH's "vanilla fade" runs after the translucent layer, i.e. after IP has rendered the portals, and read DH's colour/depth textures that the portal pass had just drawn into. DH's active textures are now copied aside before the outermost portal loop and restored afterwards (`IPDhTextureGuard`).

## [1.3.1] - 2026-10-09

### Fixed
- LODs jittered badly whenever a portal was on screen (and inside portal views). DH's temporal anti-aliasing reprojects against the previous frame's camera, matrices and colour history, and the portal pass overwrote them. The anti-aliasing is now skipped in portal passes; the outer world's history stays intact.
- DH clouds seen through a portal were black, and LODs were lit by the wrong dimension's lightmap whenever a portal was on screen. DH filed each lightmap upload under the level last drawn, while IP updates one light texture per pass, so the two dimensions' lightmaps were swapped. Uploads are now filed under the level the light texture was computed for.

## [1.3.0] - 2026-10-09

### Added
- Distant Horizons renders inside portal views. DH's own Immersive Portals hook skipped its whole pass (LODs and DH clouds) during a portal pass, so a portal showed terrain only out to the vanilla render distance under an empty sky, and the far terrain and clouds popped in on crossing. The pass now runs in portal views (DH picks the level from the renderer that is drawing, loads it on demand, renders from the portal camera and composites through IP's stencil mask), DH's fog override applies in portal views like in the outer world, and for a dimension the player only looks into through a portal DH's LOD loading is pointed at the spot being looked at instead of the player's own coordinates. `IPDhPortalView.ENABLED` switches the whole thing off.

## [1.2.8] - 2026-10-09

### Fixed
- Fast ships sometimes arrived on the wrong side of the destination portal. The driver chose the portal entity facing the hull centre at scan time; a frame has one entity per side, so a ship whose centre had already crossed the plane between two scans was handed to the entity on the far side, whose transform (plus the push-out) delivers to the entry side of the destination. The portal is now chosen by the direction of travel, using the hull centre from the previous scan: the one the ship was in front of and has moved towards, even if it has since crossed. A ship standing still keeps the old facing rule; a ship that only touches a portal while moving away from it is no longer moved at all.

## [1.2.7] - 2026-10-09

### Fixed
- Turning a lit portal into a physics object teleported the new object (and its riders) through its own portal, after which both ends stopped working but the transparent portal blocks stayed on the object. Portals do not work on physics objects, so a lit portal whose portal or frame blocks are assembled into a sub-level now breaks on the spot, on both sides, before Sable moves the blocks; the object keeps only the frame. Every assembly path (Sable commands, Create Aeronautics assemblers) is covered.
- The ship driver no longer carries a ship through a breakable portal whose blocks are no longer in place; such a portal is broken immediately instead of a few seconds later.

## [1.2.6] - 2026-10-09

### Fixed
- Ships whose hull touched a portal low arrived at the mirrored height on the other side, which put them inside the ground whenever the destination portal stood on higher terrain; Sable then let them sink until they found air. The arrival point is now raised, before the move, to the lowest height at which the ship's hull overlaps no block with a collision shape, never above the dimension's logical height (a nether arrival is not lifted above the roof). Riders follow because they are carried by the ship's actual displacement.

## [1.2.5] - 2026-10-09

### Fixed
- Ships still blinked during a dimension change and again one second after arriving. The client no longer purges any ship state on a dimension change (removed), the server no longer re-sends the arrival dimension's ships (removed; Sable's own tracking takes over and the duplicate guard keeps the existing copies), and a re-sent finalize packet for a ship the client already shows marks its render data dirty instead of recreating it.
- Through-portal sync bookkeeping on a dimension change now keeps the entries of the dimension the player left, so ships still visible through the portal keep updating and the others get a proper stop-tracking packet when they drop out of view.

## [1.2.4] - 2026-10-09

### Fixed
- Ships blinked repeatedly for a while after a portal crossing. The render watchdog recreated each ship's render data on a fixed schedule (2 s, 5 s, 10 s after sync) and again whenever it decided sections were "stuck", which it did constantly because empty sections never compile. Rebuilds now mark the existing sections dirty instead of recreating the render data (old meshes stay until the new ones are ready), only one rebuild is scheduled after a sync, and the stuck check ignores sections without blocks.
- A duplicate start-tracking packet for a ship the client already has no longer removes and recreates it; the packet is skipped and the following chunk data refreshes the ship in place.
- The client no longer purges the ships of the world it just arrived in on a dimension change.

## [1.2.3] - 2026-10-09

### Fixed
- Ships arriving through a portal were displaced: 64 blocks vertically between dimensions with different build heights (underground in the overworld, above the roof in the nether) and a whole plot sideways whenever the destination's preferred plot slot was taken. Sable keys chunk sections by array index and maps plot space through the pose's rotation point; the loader now re-keys the sections to keep absolute Y and moves the rotation point together with every plot-space shift.
- Ships could stay invisible (but solid) after the player changed dimension: the client purges all ship state on a dimension change, discarding a full sync that was already in flight. The server's arrival re-sync is now sent one second after the change instead of immediately.

### Changed
- Ship move events (entered portal, loaded into destination with the applied shifts, arrived, restored, journal recovery, physics rebuilt) are logged at info level again.

## [1.2.2] - 2026-10-09

### Changed
- Built against Sodium 0.8.13 (was 0.8.12). The update touches only Sodium's config system and native-buffer bookkeeping; every rendering class the portal compat depends on is unchanged.

## [1.2.1] - 2026-10-09

### Fixed
- Sodium: terrain faces flickered around a portal when the same dimension was rendered more than once per frame (portal-in-portal back into the player's world, same-dimension portals, mirrors). Sodium 0.8 caches each region's draw batch and only refilled it for the list that built it; the cache is now invalidated whenever a region is drawn from a different portal layer's render list.

## [1.2.0] - 2026-10-09

First release of **Immersive Portals: Aeronautics** (`ip_aeronautics`), the source reconstruction of the binary-only Immersive Aeronautics 1.1.4 fork on top of Immersive Portals for NeoForge 6.0.7. Versioning continues from the fork (1.1.4); mod ids (`immersive_portals_core`, `imm_ptl`, `q_misc_util`, alias `immersive_portals`) are unchanged so configs and worlds carry over.

### Added
- Sable / Create Aeronautics integration: ships (sub-levels) fly through portals with riders, seats and running contraptions; ships are visible through portals; Create trains path through portal tracks; Physics Mod, Distant Horizons, Iris and Veil compatibility fixes carried over from the fork.
- Compile-time targets: Sable 2.0.6, Create Aeronautics 1.3.2, Sodium 0.8.12, Iris 1.8.14-beta.1, Distant Horizons 3.3.3, Physics Mod 3.0.34.

### Changed
- Reflection into vanilla/Sable internals replaced by mixin accessors; diagnostics-only code and console spam removed; per-ship traversal state no longer leaks.
- Nag chat messages removed (Iris notice, "many mods", Quilt, wiki link).
- Physics Mod 3.0.34 API: ocean shaders are reloaded via `Iris.reloadOceanShaders()` instead of clearing removed static fields.

## [6.0.7] - 2025-06-18

### Fixed

- Oritech animations not displaying ([#13](https://github.com/iPortalTeam/ImmersivePortalsModForNeo/issues/13)).
- ComputerCraft monitors not displaying anything ([#31](https://github.com/iPortalTeam/ImmersivePortalsModForNeo/issues/31)).

## [6.0.6] - 2024-12-22

### Updated

- Sync upstream (v6.0.6)
- Sodium compat (v0.6.0)
- Iris compat (v1.8.0) (experimental)

### Fixed

- Default config values being wrong

## [6.0.3] - 2024-10-20

### Added

- Initial port to NeoForge 1.21.1

### Known Issues

- Iris compatibility is not fully functional
- Crash with SecurityCraft

[6.0.7]: https://github.com/iPortalTeam/ImmersivePortalsModForNeo/releases/tag/v6.0.7
[6.0.6]: https://github.com/iPortalTeam/ImmersivePortalsModForNeo/releases/tag/v6.0.6
[6.0.3]: https://github.com/iPortalTeam/ImmersivePortalsModForNeo/releases/tag/v6.0.3
