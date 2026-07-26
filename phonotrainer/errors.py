"""Excepciones compartidas.

`JobCancelled` vive aquí, y no en `jobs.py`, porque la lanza el callback de
progreso y tiene que atravesar módulos que no saben nada de jobs (la descarga,
por ejemplo, no debe confundirla con un fallo de red).
"""

from __future__ import annotations


class JobCancelled(RuntimeError):
    """El usuario canceló: no es un error, es una orden."""
