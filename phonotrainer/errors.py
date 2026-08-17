"""Shared exceptions.

`JobCancelled` lives here rather than in `jobs.py` because it is raised by the
progress callback and therefore has to travel through modules that know nothing
about jobs (the downloader, for instance, must not mistake it for a network
failure).
"""

from __future__ import annotations


class JobCancelled(RuntimeError):
    """The user cancelled: this is not an error, it is an order."""
