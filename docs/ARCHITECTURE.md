# Airships architecture

The server assembles blocks into ship entity state, handles fuel and motion, and places blocks on landing. The client handles rendering, input, and deck motion presentation. The game tests drive both paths.

See [compatibility](COMPATIBILITY.md) for contracts that must survive refactors.
