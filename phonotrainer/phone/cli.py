"""`phonotrainer phone …` and the phone lines of the `ui` banner.

Pairing and device management happen in the terminal only: these commands
work on the configuration directory directly and never call the HTTP API, so
they have no network surface of their own. Addresses are printed to the
terminal at run time and never written anywhere.
"""

from __future__ import annotations

import sys
import time
from datetime import datetime
from typing import Callable, Iterable

import click

from . import PAIRING_MAX_WRONG
from .config import ConfigDir, ConfigError, runtime_info
from .identity import (MAX_NAME_CHARS, ServerIdentity, active_addresses, public_note,
                       skipped_public)
from .netinfo import Interface, interfaces as real_interfaces
from .registry import DeviceRegistry
from .settings import PhoneSettings

POLL_S = 0.5

UI_COMMAND = "phonotrainer ui --phone-access"
PAIR_COMMAND = "phonotrainer phone pair"
RENAME_LINE = 'Rename this computer for your phone with: phonotrainer phone name "…"'
SHORT_ID_CHARS = 12                       # "dev_" and the first 8 characters


def _open_identity(config: ConfigDir) -> ServerIdentity:
    identity = ServerIdentity(config, time.time)
    try:
        identity.load()
    except ConfigError as exc:
        raise click.ClickException(str(exc)) from exc
    return identity


def _shown(active: list[Interface], host: str | None) -> list[Interface]:
    """The addresses a phone can use: all active approved ones, or only the one
    the server was told to bind."""
    if host in (None, "0.0.0.0", ""):
        return active
    return [iface for iface in active if iface.address == host]


# --- ui ---------------------------------------------------------------------------

def prepare_phone_access(choice: bool | None, extra_interfaces: Iterable[str],
                         netinfo: Callable[[], list[Interface]] | None = None,
                         config: ConfigDir | None = None,
                         notes: list[str] | None = None) -> PhoneSettings | None:
    """`ui --phone-access/--no-phone-access/--phone-interface`: remember the
    choice (approving the networks the computer is on now when turning it on)
    and return the settings to start the server with, or None for off.

    `--phone-interface` alone (phone access already on) approves only the
    interfaces it names: approving every network the computer is on now is
    what `--phone-access` and `phone on` do, never a side effect of naming a
    VPN or container interface on a café Wi-Fi.

    `notes` receives extra lines for the banner: on the first start (this
    call created the computer's identity), how to rename the computer."""
    netinfo = netinfo or real_interfaces
    config = config or ConfigDir()
    extra = tuple(extra_interfaces)
    if choice is None and not config.exists():
        return None                       # never chose: off, and nothing is created
    identity = _open_identity(config)
    if choice is True:
        current = netinfo()
        identity.turn_on(current, extra)
        if notes is not None:
            notes.extend(public_note(iface) for iface in skipped_public(current, extra))
    elif choice is None and identity.phone_access and extra:
        identity.turn_on([iface for iface in netinfo() if iface.name in extra], extra)
    elif choice is False:
        identity.turn_off()
    if not identity.phone_access:
        return None
    if identity.created and notes is not None:
        notes.append(RENAME_LINE)
    return PhoneSettings(config_dir=config.path, netinfo=netinfo)


def banner(settings: PhoneSettings, port: int, host: str | None = None) -> list[str]:
    """The phone lines of the `ui` start banner (no secret is ever printed)."""
    identity = _open_identity(ConfigDir(settings.config_dir))
    approved = active_addresses(identity.approved_networks, settings.netinfo())
    if not approved:
        return ["Phone access is ON, but this computer is not on a network you approved "
                "for phones.",
                "Run `phonotrainer phone on` here to approve this network."]
    active = _shown(approved, host)
    if not active:
        return [f"Phone access is ON, but this server listens on {host} only: phones "
                "cannot reach it.",
                "Start it without --host to let paired phones on this Wi-Fi reach it."]
    lines = ["Phone access is ON: paired phones on this Wi-Fi can reach this computer at"]
    lines += [f"  {iface.address}:{port}   ({iface.name})" for iface in active]
    lines += [f"Pair a phone:            {PAIR_COMMAND}",
              "Turn phone access off:   phonotrainer phone off   "
              "(or start with --no-phone-access)"]
    return lines


# --- phonotrainer phone -------------------------------------------------------------

def _fail(message: str, code: int = 1) -> None:
    click.echo(message)
    sys.exit(code)


@click.group("phone")
def phone() -> None:
    """Phone access for the Android client: pairing and paired phones."""


@phone.command("status")
def status() -> None:
    """Phone access, this computer's name and the paired phones."""
    config = ConfigDir()
    identity = _open_identity(config)
    running = runtime_info(config)
    if not identity.phone_access:
        state = "OFF"
    elif active_addresses(identity.approved_networks, real_interfaces()):
        state = "ON"
    else:
        state = "SUSPENDED (this computer is not on a network you approved for phones)"
    click.echo(f"Phone access: {state}")
    click.echo(f'Computer name: "{identity.name}"')
    click.echo(f"Server id: {identity.server_id[:8]}…")
    networks = identity.approved_networks
    if networks:
        click.echo("Approved networks:")
        for entry in networks:
            click.echo(f"  {entry.get('interface')} {entry.get('network')}")
    else:
        click.echo("Approved networks: none")
    click.echo(f"Paired phones: {len(DeviceRegistry(config, time.time).devices())}")
    if running:
        click.echo(f"Server: running (phone access {running.get('state', 'on')}), "
                   f"port {running.get('port')}")
    else:
        click.echo("Server: not running with phone access")


@phone.command("on")
def turn_on() -> None:
    """Turn phone access on and approve the networks this computer is on now."""
    config = ConfigDir()
    identity = _open_identity(config)
    current = real_interfaces()
    for entry in identity.turn_on(current):
        click.echo(f"Approved for phones: {entry['interface']} {entry['network']}")
    for iface in skipped_public(current):
        click.echo(public_note(iface))
    click.echo("Phone access is now ON.")
    if runtime_info(config) is None:
        click.echo("Restart `phonotrainer ui` to apply it.")
    if identity.created:
        click.echo(RENAME_LINE)


@phone.command("off")
def turn_off() -> None:
    """Turn phone access off (the approved networks are cleared)."""
    _open_identity(ConfigDir()).turn_off()
    click.echo("Phone access is now OFF.")


@phone.command("pair")
@click.option("--no-wait", is_flag=True, default=False,
              help="Show the code and return instead of waiting for a phone.")
def pair(no_wait: bool) -> None:
    """Create a pairing code for a phone (valid for 5 minutes, single use)."""
    config = ConfigDir()
    identity = _open_identity(config)
    if not identity.phone_access:
        _fail("Phone access is OFF. Turn it on with: phonotrainer phone on")
    running = runtime_info(config)
    if running is None:
        _fail(f"PhonoTrainer is not running with phone access on. Start it with: {UI_COMMAND}")
    registry = DeviceRegistry(config, time.time)
    code = registry.create_code()
    click.echo(f"Pairing code: {code.grouped}   (valid for 5 minutes, single use)")
    click.echo(f'On the phone: Connect to your computer, pick "{identity.name}", '
               "or enter one of these addresses:")
    for iface in active_addresses(identity.approved_networks, real_interfaces()):
        click.echo(f"  {iface.address}:{running.get('port')}   ({iface.name})")
    if no_wait:
        return
    click.echo("Waiting for a phone… (Ctrl-C cancels the code)")
    try:
        sys.exit(wait_for_pairing(registry, code.expires_at))
    except KeyboardInterrupt:
        registry.delete_code()
        _fail("Pairing code cancelled.")


def wait_for_pairing(registry: DeviceRegistry, expires_at: float,
                     clock: Callable[[], float] = time.time,
                     sleep: Callable[[float], None] = time.sleep) -> int:
    """Follow pairing.json until a phone pairs (0), the code expires (3) or
    locks (4), printing each wrong attempt."""
    reported = 0
    while True:
        try:
            state = registry.pairing_state()
        except ConfigError:
            state = None
        if state is None:
            click.echo(f"The code is gone (was the server restarted?). Run `{PAIR_COMMAND}` "
                       "for a new code.")
            return 3
        paired = state.get("paired")
        if isinstance(paired, dict):
            click.echo(f"Paired: {paired.get('name')} (from {paired.get('from')})")
            registry.delete_code()        # the address lives there only until printed
            return 0
        wrong = int(state.get("wrong_attempts") or 0)
        if wrong > reported:
            reported = wrong
            times = "time" if wrong == 1 else "times"
            if not state.get("locked"):
                click.echo(f"Wrong code tried {wrong} {times} "
                           "(another device on this network may be guessing).")
        if state.get("locked"):
            click.echo(f"The code is now locked after {PAIRING_MAX_WRONG} wrong attempts "
                       "(another device on this network may be guessing). "
                       f"Run `{PAIR_COMMAND}` for a new code.")
            registry.delete_code()
            return 4
        if clock() >= expires_at:
            click.echo(f"The code expired without a phone pairing. Run `{PAIR_COMMAND}` "
                       "for a new code.")
            registry.delete_code()
            return 3
        sleep(POLL_S)


# --- paired phones ----------------------------------------------------------------------

def _short_id(device_id: str) -> str:
    return device_id[:SHORT_ID_CHARS]


def _local_time(text: str | None) -> str:
    if not text:
        return "never"
    try:
        return datetime.fromisoformat(text).astimezone().strftime("%Y-%m-%d %H:%M")
    except ValueError:
        return "unknown"


def relative(text: str | None, now: float) -> str:
    """"just now", "5 minutes ago", "3 hours ago", "2 days ago" or "never"."""
    if not text:
        return "never"
    try:
        seconds = max(0, int(now - datetime.fromisoformat(text).timestamp()))
    except ValueError:
        return "unknown"
    for size, unit in ((86400, "day"), (3600, "hour"), (60, "minute")):
        if seconds >= size:
            count = seconds // size
            return f"{count} {unit}{'s' if count != 1 else ''} ago"
    return "just now"


def _phone_line(device, now: float) -> str:
    version = f"{device.app_version_name} ({device.app_version})" if device.app_version_name \
        else str(device.app_version)
    line = (f"{device.name}   {_short_id(device.device_id)}   paired "
            f"{_local_time(device.paired_at)}   last seen {relative(device.last_seen_at, now)}   "
            f"app {version}")
    if device.revoked:
        line += f"   revoked {_local_time(device.revoked_at)}"
    return line


@phone.command("list")
@click.option("--all", "show_all", is_flag=True, default=False,
              help="Also list revoked phones.")
def list_phones(show_all: bool) -> None:
    """Paired phones: name, id, paired at, last seen, app version."""
    registry = DeviceRegistry(ConfigDir(), time.time)
    devices = sorted(registry.devices(include_revoked=show_all), key=lambda d: d.paired_at)
    if not devices:
        click.echo("No paired phones." if not show_all else "No phones have ever paired.")
        return
    now = time.time()
    for device in devices:
        click.echo(_phone_line(device, now))


@phone.command("revoke")
@click.argument("target", required=False)
@click.option("--all", "revoke_all", is_flag=True, default=False, help="Revoke every phone.")
@click.option("--yes", is_flag=True, default=False, help="Do not ask before revoking every phone.")
def revoke(target: str | None, revoke_all: bool, yes: bool) -> None:
    """Revoke a phone (by name or the start of its id); it stops working at
    its next request."""
    registry = DeviceRegistry(ConfigDir(), time.time)
    if revoke_all:
        active = registry.devices()
        if not active:
            click.echo("No paired phones.")
            return
        if not yes and not click.confirm(f"Revoke all {len(active)} phones?", default=False):
            click.echo("Nothing was revoked.")
            return
        count = registry.revoke_all()
        click.echo(f"Revoked {count} phone{'s' if count != 1 else ''}.")
        return
    if not target:
        raise click.UsageError("Name a phone (its name or the start of its id), or use --all.")
    active = registry.devices()
    exact = [d for d in active if d.device_id == target]
    matches = exact or [d for d in active
                        if d.device_id.startswith(target) or d.name.casefold() == target.casefold()]
    if not matches:
        _fail(f'No paired phone matches "{target}". See `phonotrainer phone list`.', 1)
    if len(matches) > 1:
        click.echo(f'More than one phone matches "{target}"; use more of its id:')
        now = time.time()
        for device in matches:
            click.echo(f"  {_phone_line(device, now)}")
        sys.exit(2)
    device = matches[0]
    registry.revoke(device.device_id)
    click.echo(f"Revoked: {device.name} ({_short_id(device.device_id)}). "
               "It stops working at its next request.")


@phone.command("name")
@click.argument("name")
def rename(name: str) -> None:
    """Rename this computer as phones see it (1 to 40 characters)."""
    identity = _open_identity(ConfigDir())
    try:
        cleaned = identity.rename(name)
    except ValueError:
        _fail(f"The name must be 1 to {MAX_NAME_CHARS} characters long "
              "(control characters are removed).", 1)
    click.echo(f'Phones now see this computer as "{cleaned}".')
