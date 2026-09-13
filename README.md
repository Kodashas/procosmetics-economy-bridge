# ProCosmetics Economy Bridge

Makes [ProCosmetics](https://www.spigotmc.org/resources/procosmetics-350-cosmetics-treasure-chests.137754/) charge cosmetic
purchases against an **ExcellentEconomy** currency instead of its own internal coin balance.

If your server already runs ExcellentEconomy and you want cosmetics paid for with a currency
players actually earn — coins, tokens, gems, whatever you have configured — this is the piece
in between.

ProCosmetics can already talk to Vault, but Vault exposes a single balance. ExcellentEconomy
has named currencies, and this plugin points cosmetics at exactly one of them.

## What it does

ProCosmetics accepts exactly one `EconomyProvider`. This plugin registers its own at enable
time, replacing the built-in one. Nothing else about ProCosmetics changes: menus, cosmetics,
permissions and prices stay as they are, they are just paid for from a different balance.

Every operation has two paths:

- player online → synchronous ExcellentEconomy call
- player offline → the async, UUID-keyed call

ProCosmetics may call the provider off the main thread. Balance operations run on the calling
thread, which ExcellentEconomy supports; only messages to a player are pushed back onto the
main thread.

A withdrawal checks the balance first. ExcellentEconomy clamps a balance at zero and still
reports success, so without that check taking more than a player owns would read as a paid
purchase. The check and the withdrawal are two operations rather than one atomic one, so a
balance change landing between them can still slip through.

The purchase message is sent from listeners on ProCosmetics' three purchase events, not from
the provider: `removeCoinsAsync` is a generic balance operation that an admin
`/procosmetics remove coins` also goes through, and announcing a payment there told players
they had bought something when nothing had been bought.

## Requirements

- Paper or a Paper fork, Minecraft 26.1+ (ProCosmetics 2.0.7 requirement; built against `paper-api` 26.2)
- Java 25+ on the server — required by ProCosmetics 2.0.7 itself. This plugin targets release 21, so it runs on anything from 21 upwards.
- [ProCosmetics](https://www.spigotmc.org/resources/procosmetics-350-cosmetics-treasure-chests.137754/)
- [ExcellentEconomy](https://modrinth.com/plugin/excellenteconomy), and nightcore which it depends on

Both plugins are hard dependencies in `plugin.yml`, so the server refuses to enable this
plugin without them.

## Configuration

`plugins/ProCosmeticsEconomyBridge/config.yml`:

```yaml
currency-id: coins
currency-name: coins
purchase-success-message: <green>Paid <yellow><amount> <currency></yellow><green>.</green>
debug: false
```

| Key | Meaning |
|---|---|
| `currency-id` | Currency id as configured in ExcellentEconomy |
| `currency-name` | How the currency is written in messages, in whatever grammatical form your language needs |
| `purchase-success-message` | MiniMessage, sent after a successful purchase |
| `debug` | Logs every balance operation at INFO |

The message supports `<amount>` and `<currency>`.

Nothing is sent when a withdrawal fails: ProCosmetics already messages the player from its
own purchase menu, so a second message would double up. The failure is logged at WARNING.

The "not enough coins" message is deliberately **not** configured here. It comes from
ProCosmetics' own `player.not_enough_coins` translation key, fed the same two placeholders, so
it follows your ProCosmetics language file and stays consistent with its other menus.

The currency is resolved during enable, before the provider is registered. A wrong
`currency-id` therefore fails the enable with a clear error instead of surfacing later, in
front of a player.

## Building

All three plugins this compiles against are free to download, but none of them publishes to a
Maven repository, so they cannot be resolved automatically. Put their jars in `libs/` first.

The easiest source is a server that already runs them:

```bash
scripts/fetch-libs.sh /path/to/server/plugins
mvn clean package
```

Otherwise download them by hand and name them `libs/ProCosmetics.jar`,
`libs/ExcellentEconomy.jar` and `libs/nightcore.jar`.

`libs/` is gitignored — those are other people's plugins and are not redistributed here.
`paper-api` comes from the PaperMC repository and fastutil from Maven Central; both are
resolved by Maven.

Output: `target/ProCosmeticsEconomyBridge-1.0.0.jar`.

There is one self-check, covering message rendering — the only part that runs without a
server. It asserts that both placeholders are substituted, that a blank template sends
nothing, and that a currency name containing markup is inserted as text rather than parsed as
a MiniMessage tag:

```bash
scripts/check.sh
```

It prints `RenderCheck: ok` and exits 0 when everything holds.

## Installing

Drop the jar into `plugins/` and restart. Set `currency-id` to match your ExcellentEconomy
currency, then restart once more.

## Licence

MIT. See [LICENSE](LICENSE).
