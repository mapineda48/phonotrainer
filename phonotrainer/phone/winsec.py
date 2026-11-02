"""Windows owners and DACLs of the configuration directory (advapi32 through
ctypes). Imported only when `sys.platform == "win32"`: on Linux and macOS the
configuration directory uses the owner uid and the mode bits instead.

The Windows equivalent of a `0700` directory with `0600` files is a protected
DACL (nothing inherited from the parent) that grants full control to the
current user, SYSTEM and Administrators only, inherited by the files created
in it. The owner of the directory and of its secret files must be the current
user (or the token's default owner, which is Administrators for an elevated
process, or SYSTEM): a directory another user created is refused.
"""

from __future__ import annotations

import ctypes
import functools
from ctypes import wintypes
from pathlib import Path

_advapi32 = ctypes.WinDLL("advapi32", use_last_error=True)
_kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)

SE_FILE_OBJECT = 1
OWNER_SECURITY_INFORMATION = 0x00000001
DACL_SECURITY_INFORMATION = 0x00000004
PROTECTED_DACL_SECURITY_INFORMATION = 0x80000000
SDDL_REVISION_1 = 1
TOKEN_QUERY = 0x0008
TOKEN_USER_CLASS = 1
TOKEN_OWNER_CLASS = 4
ACL_SIZE_INFORMATION_CLASS = 2
# ACE types that grant access; the SID of the two plain ones follows the
# 4-byte header and the 4-byte mask. The object types put it elsewhere.
ACCESS_ALLOWED_ACE_TYPE = 0x0
ACCESS_ALLOWED_CALLBACK_ACE_TYPE = 0x9
ALLOWED_OBJECT_ACE_TYPES = (0x5, 0xB)

SYSTEM_SID = "S-1-5-18"
ADMINISTRATORS_SID = "S-1-5-32-544"
CREATOR_OWNER_SID = "S-1-3-0"       # stands for the creator in inherited ACEs
UNKNOWN_GRANTEE = "?"               # an ACE this module does not parse: never trusted

PSID = wintypes.LPVOID
PACL = wintypes.LPVOID
PSD = wintypes.LPVOID


class _AclSizeInformation(ctypes.Structure):
    _fields_ = [("AceCount", wintypes.DWORD), ("AclBytesInUse", wintypes.DWORD),
                ("AclBytesFree", wintypes.DWORD)]


class _AceHeader(ctypes.Structure):
    _fields_ = [("AceType", ctypes.c_ubyte), ("AceFlags", ctypes.c_ubyte),
                ("AceSize", wintypes.WORD)]


_advapi32.GetNamedSecurityInfoW.argtypes = (
    wintypes.LPCWSTR, ctypes.c_int, wintypes.DWORD, ctypes.POINTER(PSID), ctypes.POINTER(PSID),
    ctypes.POINTER(PACL), ctypes.POINTER(PACL), ctypes.POINTER(PSD))
_advapi32.GetNamedSecurityInfoW.restype = wintypes.DWORD
_advapi32.SetNamedSecurityInfoW.argtypes = (
    wintypes.LPWSTR, ctypes.c_int, wintypes.DWORD, PSID, PSID, PACL, PACL)
_advapi32.SetNamedSecurityInfoW.restype = wintypes.DWORD
_advapi32.ConvertStringSecurityDescriptorToSecurityDescriptorW.argtypes = (
    wintypes.LPCWSTR, wintypes.DWORD, ctypes.POINTER(PSD), ctypes.POINTER(wintypes.ULONG))
_advapi32.ConvertStringSecurityDescriptorToSecurityDescriptorW.restype = wintypes.BOOL
_advapi32.GetSecurityDescriptorDacl.argtypes = (
    PSD, ctypes.POINTER(wintypes.BOOL), ctypes.POINTER(PACL), ctypes.POINTER(wintypes.BOOL))
_advapi32.GetSecurityDescriptorDacl.restype = wintypes.BOOL
_advapi32.ConvertSidToStringSidW.argtypes = (PSID, ctypes.POINTER(wintypes.LPWSTR))
_advapi32.ConvertSidToStringSidW.restype = wintypes.BOOL
_advapi32.GetAclInformation.argtypes = (PACL, wintypes.LPVOID, wintypes.DWORD, ctypes.c_int)
_advapi32.GetAclInformation.restype = wintypes.BOOL
_advapi32.GetAce.argtypes = (PACL, wintypes.DWORD, ctypes.POINTER(wintypes.LPVOID))
_advapi32.GetAce.restype = wintypes.BOOL
_advapi32.OpenProcessToken.argtypes = (wintypes.HANDLE, wintypes.DWORD,
                                       ctypes.POINTER(wintypes.HANDLE))
_advapi32.OpenProcessToken.restype = wintypes.BOOL
_advapi32.GetTokenInformation.argtypes = (wintypes.HANDLE, ctypes.c_int, wintypes.LPVOID,
                                          wintypes.DWORD, ctypes.POINTER(wintypes.DWORD))
_advapi32.GetTokenInformation.restype = wintypes.BOOL
_kernel32.GetCurrentProcess.argtypes = ()
_kernel32.GetCurrentProcess.restype = wintypes.HANDLE
_kernel32.CloseHandle.argtypes = (wintypes.HANDLE,)
_kernel32.CloseHandle.restype = wintypes.BOOL
_kernel32.LocalFree.argtypes = (wintypes.LPVOID,)
_kernel32.LocalFree.restype = wintypes.LPVOID


def _check(ok, what: str) -> None:
    if not ok:
        raise ctypes.WinError(ctypes.get_last_error(), f"{what} failed")


def _sid_string(sid) -> str:
    text = wintypes.LPWSTR()
    _check(_advapi32.ConvertSidToStringSidW(sid, ctypes.byref(text)), "ConvertSidToStringSidW")
    try:
        return text.value
    finally:
        _kernel32.LocalFree(text)


def _token_sid(info_class: int) -> str:
    """The SID of TOKEN_USER or TOKEN_OWNER (both start with a SID pointer)."""
    token = wintypes.HANDLE()
    _check(_advapi32.OpenProcessToken(_kernel32.GetCurrentProcess(), TOKEN_QUERY,
                                      ctypes.byref(token)), "OpenProcessToken")
    try:
        size = wintypes.DWORD()
        _advapi32.GetTokenInformation(token, info_class, None, 0, ctypes.byref(size))
        if not size.value:
            raise ctypes.WinError(ctypes.get_last_error(), "GetTokenInformation failed")
        buffer = ctypes.create_string_buffer(size.value)
        _check(_advapi32.GetTokenInformation(token, info_class, buffer, size, ctypes.byref(size)),
               "GetTokenInformation")
        return _sid_string(ctypes.cast(buffer, ctypes.POINTER(wintypes.LPVOID))[0])
    finally:
        _kernel32.CloseHandle(token)


@functools.lru_cache(maxsize=1)
def current_user_sid() -> str:
    return _token_sid(TOKEN_USER_CLASS)


@functools.lru_cache(maxsize=1)
def trusted_owners() -> frozenset[str]:
    """Owners that are not "another user": this user, the owner this process
    gives the files it creates (Administrators when elevated), SYSTEM and
    Administrators (who are granted full control anyway)."""
    return frozenset({current_user_sid(), _token_sid(TOKEN_OWNER_CLASS), SYSTEM_SID,
                      ADMINISTRATORS_SID})


def _named_security(path: Path, what: int):
    owner, dacl, descriptor = PSID(), PACL(), PSD()
    error = _advapi32.GetNamedSecurityInfoW(str(path), SE_FILE_OBJECT, what,
                                            ctypes.byref(owner), None, ctypes.byref(dacl),
                                            None, ctypes.byref(descriptor))
    if error:
        raise ctypes.WinError(error, "GetNamedSecurityInfoW failed")
    return owner, dacl, descriptor


def owner_sid(path: Path) -> str:
    owner, _, descriptor = _named_security(path, OWNER_SECURITY_INFORMATION)
    try:
        return _sid_string(owner)
    finally:
        _kernel32.LocalFree(descriptor)


def owned_by_me(path: Path) -> bool:
    return owner_sid(path) in trusted_owners()


def grantees(path: Path) -> set[str]:
    """The SIDs that an access-allowed ACE of the DACL grants anything to,
    including inherit-only ACEs (the files created later would get them).
    A missing (NULL) DACL grants everyone everything."""
    _, dacl, descriptor = _named_security(path, DACL_SECURITY_INFORMATION)
    try:
        if not dacl:
            return {UNKNOWN_GRANTEE}
        info = _AclSizeInformation()
        _check(_advapi32.GetAclInformation(dacl, ctypes.byref(info), ctypes.sizeof(info),
                                           ACL_SIZE_INFORMATION_CLASS), "GetAclInformation")
        found = set()
        for index in range(info.AceCount):
            ace = wintypes.LPVOID()
            _check(_advapi32.GetAce(dacl, index, ctypes.byref(ace)), "GetAce")
            header = _AceHeader.from_address(ace.value)
            if header.AceType in (ACCESS_ALLOWED_ACE_TYPE, ACCESS_ALLOWED_CALLBACK_ACE_TYPE):
                mask = wintypes.DWORD.from_address(ace.value + 4).value
                if mask:
                    found.add(_sid_string(ace.value + 8))
            elif header.AceType in ALLOWED_OBJECT_ACE_TYPES:
                found.add(UNKNOWN_GRANTEE)
        return found
    finally:
        _kernel32.LocalFree(descriptor)


def foreign_grantees(path: Path) -> set[str]:
    """Who else the DACL lets in: empty when only this user (or the trusted
    owners, or the creator of new files) is granted anything."""
    return grantees(path) - trusted_owners() - {CREATOR_OWNER_SID}


def make_private(path: Path, directory: bool) -> None:
    """A protected DACL with full control for this user, SYSTEM and
    Administrators only; a directory's is inherited by its files and
    subdirectories (existing ones get it too)."""
    inherit = "OICI" if directory else ""
    sddl = "D:P" + "".join(f"(A;{inherit};FA;;;{sid})"
                           for sid in (current_user_sid(), SYSTEM_SID, ADMINISTRATORS_SID))
    descriptor = PSD()
    _check(_advapi32.ConvertStringSecurityDescriptorToSecurityDescriptorW(
        sddl, SDDL_REVISION_1, ctypes.byref(descriptor), None),
        "ConvertStringSecurityDescriptorToSecurityDescriptorW")
    try:
        present, defaulted, dacl = wintypes.BOOL(), wintypes.BOOL(), PACL()
        _check(_advapi32.GetSecurityDescriptorDacl(descriptor, ctypes.byref(present),
                                                   ctypes.byref(dacl), ctypes.byref(defaulted)),
               "GetSecurityDescriptorDacl")
        error = _advapi32.SetNamedSecurityInfoW(
            str(path), SE_FILE_OBJECT,
            DACL_SECURITY_INFORMATION | PROTECTED_DACL_SECURITY_INFORMATION,
            None, None, dacl, None)
        if error:
            raise ctypes.WinError(error, "SetNamedSecurityInfoW failed")
    finally:
        _kernel32.LocalFree(descriptor)
