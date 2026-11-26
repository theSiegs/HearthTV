"""Connects to an ESPHome Bluetooth proxy the way Home Assistant does (aioesphomeapi) and prints
what it says: device info, scanner state and raw advertisements.

    python esphome_proxy_client.py HOST [--port 6053] [--key BASE64] [--seconds 30] [--expect N]

Exit code 0 when the connection worked and, with --expect N, at least N advertisements arrived.
Used by AioesphomeapiInteropTest (docs/design/esphome-proxy.md) and by hand against a tablet.
"""
import argparse
import asyncio
import sys

from aioesphomeapi import APIClient
from aioesphomeapi.model import BluetoothProxyFeature


def fmt_mac(address: int) -> str:
    return ":".join(f"{(address >> (8 * i)) & 0xFF:02X}" for i in range(5, -1, -1))


async def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("host")
    p.add_argument("--port", type=int, default=6053)
    p.add_argument("--key", default=None, help="the proxy's base64 encryption key (Noise)")
    p.add_argument("--seconds", type=float, default=30)
    p.add_argument("--expect", type=int, default=0, help="stop once this many advertisements arrived")
    p.add_argument("--quiet", action="store_true", help="don't print each advertisement")
    a = p.parse_args()

    cli = APIClient(a.host, a.port, None, noise_psk=a.key, client_info="esphome-proxy-test")
    await cli.connect(login=True)
    info = await cli.device_info()
    flags = info.bluetooth_proxy_feature_flags_compat(cli.api_version)
    print(f"connected: name={info.name} friendly={info.friendly_name!r} mac={info.mac_address} "
          f"bt_mac={info.bluetooth_mac_address} version={info.esphome_version} api={cli.api_version} "
          f"area={info.suggested_area!r} model={info.model!r}")
    print(f"feature flags: {BluetoothProxyFeature(flags)!r}")
    entities, services = await cli.list_entities_services()
    print(f"entities: {len(entities)} services: {len(services)}")

    count = 0
    done = asyncio.Event()
    seen = set()

    def on_state(state) -> None:
        print(f"scanner state: {state.state.name} mode={state.mode.name} configured={state.configured_mode.name}")

    def on_ads(msg) -> None:
        nonlocal count
        for ad in msg.advertisements:
            count += 1
            seen.add(ad.address)
            if not a.quiet:
                print(f"ad {fmt_mac(ad.address)} type={ad.address_type} rssi={ad.rssi} data={bytes(ad.data).hex()}")
        if a.expect and count >= a.expect:
            done.set()

    if flags & BluetoothProxyFeature.FEATURE_STATE_AND_MODE:
        cli.subscribe_bluetooth_scanner_state(on_state)
    unsub = cli.subscribe_bluetooth_le_raw_advertisements(on_ads)
    try:
        await asyncio.wait_for(done.wait(), a.seconds)
    except TimeoutError:
        pass
    unsub()
    await cli.disconnect()
    print(f"advertisements: {count} from {len(seen)} addresses")
    return 0 if count >= a.expect else 1


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
