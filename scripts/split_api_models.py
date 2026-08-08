#!/usr/bin/env python3
"""Extract nested @Serializable DTOs from ApiService.kt into domain model files."""
from __future__ import annotations

from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
API = ROOT / "app/src/main/java/com/maodouchat/network/ApiService.kt"
OUT_DIR = ROOT / "app/src/main/java/com/maodouchat/network"
SRC_ROOT = ROOT / "app/src"

DOMAIN = {
    "AuthApiModels": {
        "LoginRequest",
        "RegisterRequest",
        "AuthResponse",
        "RefreshTokenRequest",
        "SendCodeRequest",
        "RegisterWithCodeRequest",
        "ChangePasswordRequest",
        "DeleteAccountRequest",
        "DeleteAccountResponse",
        "UploadAvatarRequest",
        "UpdateProfileRequest",
        "AvatarResponse",
        "UserDto",
        "UserPrivacyDto",
        "UpdatePrivacyRequest",
        "UpdateDeviceNameRequest",
        "ConfirmDeviceRequest",
        "DeviceInfoDto",
        "PreKeyDataDto",
        "UploadKeysRequest",
        "PreKeyBundleDto",
        "DevicePreKeyBundleDto",
        "ErrorResponse",
    },
    "ChatApiModels": {
        "ChatDto",
        "UpdateChatSettingsRequest",
        "ChatSettingsResponse",
        "CreateChatRequest",
        "GroupMembersRequest",
        "GroupMemberDto",
        "UpdateMemberRoleRequest",
        "UpdateGroupNicknameRequest",
        "UpdateMemberTitleRequest",
        "UpdateMemberMuteRequest",
        "UpdateGroupAnnouncementRequest",
        "GroupInviteResponse",
        "CreateGroupInviteRequest",
        "GroupAuditLogDto",
        "JoinGroupInviteRequest",
        "SenderKeyDistributionTargetRequest",
        "SenderKeyDistributionReportRequest",
        "SenderKeyDistributionTargetDto",
        "SenderKeyDistributionStatusDto",
    },
    "MessageApiModels": {
        "MessageDto",
        "MessageMutationDto",
        "UnreadWindowDto",
        "SendMessageRequest",
        "UpdateMessageReactionRequest",
        "MessageReactionUpdatedResponse",
        "StarMessageResponse",
        "ReadReceiptDto",
        "UpdateStatusRequest",
        "MarkReadResponse",
        "BatchReadRequest",
    },
    "AttachmentApiModels": {
        "AttachmentUploadResponse",
        "AttachmentCommitRequest",
        "AttachmentUploadSessionRequest",
        "AttachmentUploadStatusResponse",
    },
    "SocialApiModels": {
        "PostDto",
        "PostCommentDto",
        "CreatePostRequest",
        "CreateCommentRequest",
        "UploadPostImageRequest",
        "UploadPostImageResponse",
        "EditPostRequest",
        "UpdateNearbyLocationRequest",
        "NearbyLocationStatusResponse",
        "NearbyUserResponse",
    },
    "CallApiModels": {
        "SignalingSendRequest",
        "SignalMessageDto",
        "IceServerDto",
        "IceConfigDto",
    },
    "NotificationApiModels": {
        "NotificationSettingsRequest",
        "NotificationSettingsResponse",
        "RegisterPushTokenRequest",
        "RemovePushTokenRequest",
    },
    "ModerationApiModels": {
        "CreateReportRequest",
        "ReportResponse",
        "UpdateReportStatusRequest",
        "ApplyReportActionRequest",
        "ModerationRuleResponse",
        "UpdateModerationRuleRequest",
        "RiskEventResponse",
    },
}

EXTRA_IMPORTS = {
    "MessageApiModels": ["import com.maodouchat.data.model.MessageReaction"],
}


def find_blocks(lines: list[str]) -> list[tuple[int, int, str, list[str], bool]]:
    blocks: list[tuple[int, int, str, list[str], bool]] = []
    i = 0
    while i < len(lines):
        line = lines[i]
        if "@Serializable" in line and line.lstrip().startswith("@Serializable"):
            start = i
            j = i - 1
            # Include KDoc immediately above
            while j >= 0:
                stripped = lines[j].strip()
                if stripped.startswith("/**"):
                    start = j
                    break
                if stripped.startswith("*") or stripped.startswith("*/"):
                    start = j
                    j -= 1
                    continue
                if stripped == "" and j == i - 1:
                    j -= 1
                    continue
                break

            k = i + 1
            while k < len(lines) and "data class" not in lines[k] and "class " not in lines[k]:
                k += 1
            if k >= len(lines):
                i += 1
                continue
            m = re.search(r"(?:data\s+)?class\s+(\w+)", lines[k])
            if not m:
                i += 1
                continue
            name = m.group(1)

            brace = 0
            started = False
            end = k + 1
            for t in range(k, len(lines)):
                for ch in lines[t]:
                    if ch == "{":
                        brace += 1
                        started = True
                    elif ch == "}":
                        brace -= 1
                if not started and lines[t].rstrip().endswith(")"):
                    end = t + 1
                    break
                if started and brace == 0:
                    end = t + 1
                    break

            raw = lines[start:end]
            nested = all((not l.strip()) or l.startswith("    ") for l in raw)
            if nested:
                raw = [l[4:] if l.startswith("    ") else l for l in raw]
            # drop trailing blank lines in body
            while raw and raw[-1].strip() == "":
                raw.pop()
            raw.append("\n")
            blocks.append((start, end, name, raw, nested))
            i = end
            continue
        i += 1
    return blocks


def main() -> None:
    text = API.read_text(encoding="utf-8")
    lines = text.splitlines(keepends=True)
    blocks = find_blocks(lines)
    print(f"Found {len(blocks)} serializable data classes:")
    for s, e, n, _, nested in blocks:
        print(f"  {n}: lines {s + 1}-{e} nested={nested}")

    name_to_domain: dict[str, str] = {}
    for domain, names in DOMAIN.items():
        for n in names:
            name_to_domain[n] = domain

    unmapped = [n for _, _, n, _, _ in blocks if n not in name_to_domain]
    print("Unmapped:", unmapped)
    if unmapped:
        raise SystemExit(1)

    by_domain: dict[str, list[tuple[str, list[str]]]] = {d: [] for d in DOMAIN}
    for s, e, n, raw, nested in blocks:
        by_domain[name_to_domain[n]].append((n, raw))

    for domain, items in by_domain.items():
        parts = [
            "package com.maodouchat.network\n",
            "\n",
            "import kotlinx.serialization.Serializable\n",
        ]
        for imp in EXTRA_IMPORTS.get(domain, []):
            parts.append(imp + "\n")
        parts.append("\n")
        for n, raw in items:
            body = "".join(raw).rstrip() + "\n\n"
            parts.append(body)
        path = OUT_DIR / f"{domain}.kt"
        path.write_text("".join(parts).rstrip() + "\n", encoding="utf-8")
        print(f"Wrote {path.relative_to(ROOT)} ({len(items)} types)")

    # Remove nested class blocks from ApiService (reverse order)
    to_remove = sorted([(s, e) for s, e, n, _, nested in blocks if nested], reverse=True)
    new_lines = lines[:]
    for s, e in to_remove:
        # also trim a blank line after the block if present to avoid huge gaps
        end = e
        if end < len(new_lines) and new_lines[end].strip() == "":
            end += 1
        del new_lines[s:end]

    content = "".join(new_lines)
    content = re.sub(r"\n{3,}", "\n\n", content)
    API.write_text(content, encoding="utf-8")
    print(f"ApiService now {len(content.splitlines())} lines")

    all_names = sorted(name_to_domain.keys(), key=len, reverse=True)
    updated_files = 0
    for path in SRC_ROOT.rglob("*.kt"):
        if path.name.endswith("ApiModels.kt") or path.name == "ApiService.kt":
            continue
        src = path.read_text(encoding="utf-8")
        orig = src
        for n in all_names:
            src = src.replace(f"ApiService.{n}", n)
        if src == orig:
            continue
        if "import com.maodouchat.network.ApiService" in src:
            used = []
            for n in all_names:
                if re.search(rf"\b{n}\b", src):
                    used.append(n)
            for n in used:
                imp = f"import com.maodouchat.network.{n}"
                if imp not in src and "import com.maodouchat.network.*" not in src:
                    src = src.replace(
                        "import com.maodouchat.network.ApiService",
                        f"import com.maodouchat.network.ApiService\n{imp}",
                    )
        path.write_text(src, encoding="utf-8")
        updated_files += 1
        print(f"Updated refs in {path.relative_to(ROOT)}")

    print(f"Updated {updated_files} consumer files")


if __name__ == "__main__":
    main()
