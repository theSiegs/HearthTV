# Hearth TV's documentation

**For families using Hearth**
- [User guide](user-guide.md): setup and every feature
- [Home Assistant](home-assistant.md): what Hearth TV and Home Assistant give each other, and how to set each part up
- [Privacy](privacy.md): what Hearth TV sends where, and why it asks for each Android permission

**For contributors and apps that work with Hearth TV**
- [Development](development.md): building, testing and releasing
- [Home Assistant integration](https://github.com/theSiegs/Hearth/blob/master/docs/home-assistant-integration.md): design draft for a Hearth integration that replaces the webhook, helpers and tokens
- [Message hub](https://github.com/theSiegs/Hearth/blob/master/docs/message-hub.md): design and task list for keeping the household's SMS, RCS and other messages in
  one store the household runs, checked by a local model, with alerts to adults through Hearth
- [Hearth TV contract](https://github.com/theSiegs/Hearth/blob/master/docs/contracts/hearth-tv.md) (in Hearth, the server's
  repository): everything the TV and Hearth's Home Assistant integration say to each other, and what the TV does
  without Home Assistant. A change to it needs that page, Hearth's contract tests and this repository's
  `TvStateDocumentContractTest` and `TvStateDocumentFixtureTest`, with both copies of the fixture, changed together
- [Household sync](household-sync.md): design draft for keeping profiles, PINs, looks and Continue Watching in step across TVs
- [PIN recipes](pin-recipes.md): how Hearth TV recognises and types into each streaming app's PIN screen, and how to fix a recipe without a release
- [Provider contract](provider-contract.md): what HearthTube (or another app) can read from Hearth TV, and
  [wallpaper sync](wallpaper-sync.md), how HearthTube shows Hearth TV's wallpaper with it
