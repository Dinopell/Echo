"""
Project Echo — AES-256-GCM 加解密工具

使用 AES-256-GCM 模式进行对称加密，保护敏感数据（如转写文本）。
- 每次加密使用随机 12 字节 IV（Nonce）
- 包含 16 字节认证标签（Authentication Tag）
- 编码格式：Base64(IV + Tag + Ciphertext)

隐私原则：密鑰不存储在代码中，从环境变量读取。
"""

import base64
import hashlib
import logging
import os
import secrets
from typing import Optional

logger = logging.getLogger(__name__)

# 加密参数
AES_KEY_SIZE = 32    # AES-256
GCM_IV_SIZE = 12     # GCM 推荐 96-bit IV
GCM_TAG_SIZE = 16    # GCM 认证标签


def derive_key(passphrase: str, salt: Optional[bytes] = None) -> tuple[bytes, bytes]:
    """
    从口令派生 AES-256 密钥（使用 PBKDF2-HMAC-SHA256）

    Args:
        passphrase: 口令字符串
        salt: 盐值（不指定则随机生成 16 字节）

    Returns:
        (key_bytes, salt_bytes): 派生密钥和使用的盐值
    """
    if salt is None:
        salt = secrets.token_bytes(16)
    key = hashlib.pbkdf2_hmac(
        "sha256",
        passphrase.encode("utf-8"),
        salt,
        iterations=100_000,
        dklen=AES_KEY_SIZE,
    )
    return key, salt


def encrypt_aes_gcm(plaintext: str, key: bytes) -> str:
    """
    AES-256-GCM 加密

    Args:
        plaintext: 明文字符串
        key: 32 字节 AES 密钥

    Returns:
        str: Base64 编码的密文（格式：IV + Tag + Ciphertext）

    Raises:
        ImportError: 当 cryptography 库未安装时
        ValueError: 密钥长度不正确时
    """
    if len(key) != AES_KEY_SIZE:
        raise ValueError(f"AES 密钥长度必须为 {AES_KEY_SIZE} 字节，实际为 {len(key)}")

    try:
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    except ImportError:
        logger.error("cryptography 库未安装，请运行: pip install cryptography")
        raise

    iv = secrets.token_bytes(GCM_IV_SIZE)
    aesgcm = AESGCM(key)
    # 加密（输出为 ciphertext + tag，cryptography 库自动附加 tag）
    ciphertext_with_tag = aesgcm.encrypt(iv, plaintext.encode("utf-8"), None)

    # 格式：IV(12) + Ciphertext+Tag
    combined = iv + ciphertext_with_tag
    return base64.b64encode(combined).decode("ascii")


def decrypt_aes_gcm(ciphertext_b64: str, key: bytes) -> str:
    """
    AES-256-GCM 解密

    Args:
        ciphertext_b64: Base64 编码的密文
        key: 32 字节 AES 密钥

    Returns:
        str: 解密后的明文字符串

    Raises:
        ValueError: 密钥长度错误或密文损坏
        cryptography.exceptions.InvalidTag: 认证标签验证失败（密文被篡改）
    """
    if len(key) != AES_KEY_SIZE:
        raise ValueError(f"AES 密钥长度必须为 {AES_KEY_SIZE} 字节")

    try:
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    except ImportError:
        logger.error("cryptography 库未安装，请运行: pip install cryptography")
        raise

    try:
        combined = base64.b64decode(ciphertext_b64)
    except Exception as e:
        raise ValueError(f"密文 Base64 解码失败: {e}") from e

    if len(combined) < GCM_IV_SIZE + GCM_TAG_SIZE:
        raise ValueError("密文数据长度不足，可能已损坏")

    iv = combined[:GCM_IV_SIZE]
    ciphertext_with_tag = combined[GCM_IV_SIZE:]

    aesgcm = AESGCM(key)
    plaintext_bytes = aesgcm.decrypt(iv, ciphertext_with_tag, None)
    return plaintext_bytes.decode("utf-8")


def decrypt_audio_bytes(encrypted_bytes: bytes, key: bytes) -> bytes:
    """
    解密控制面上传的 AES-256-GCM 音频字节

    格式与 Java AudioService 一致：[12 字节 IV] + [密文 + 16 字节 Tag]

    Args:
        encrypted_bytes: MinIO 中的加密音频原始字节
        key: 32 字节 AES 密钥

    Returns:
        解密后的音频字节
    """
    if len(key) != AES_KEY_SIZE:
        raise ValueError(f"AES 密钥长度必须为 {AES_KEY_SIZE} 字节")

    if len(encrypted_bytes) < GCM_IV_SIZE + GCM_TAG_SIZE:
        raise ValueError("加密音频数据长度不足")

    try:
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    except ImportError:
        logger.error("cryptography 库未安装，请运行: pip install cryptography")
        raise

    iv = encrypted_bytes[:GCM_IV_SIZE]
    ciphertext_with_tag = encrypted_bytes[GCM_IV_SIZE:]
    aesgcm = AESGCM(key)
    return aesgcm.decrypt(iv, ciphertext_with_tag, None)


def get_audio_encrypt_key_from_env() -> Optional[bytes]:
    """
    从 AUDIO_ENCRYPT_KEY 环境变量读取音频解密密钥（与控制面一致）

    Returns:
        32 字节密钥，未配置时返回 None
    """
    return get_encryption_key_from_env("AUDIO_ENCRYPT_KEY")


def get_encryption_key_from_env(env_var: str = "AUDIO_ENCRYPT_KEY") -> Optional[bytes]:
    """
    从环境变量读取加密密钥

    环境变量格式：Base64 编码的 32 字节密钥

    Args:
        env_var: 环境变量名，默认 ECHO_ENCRYPTION_KEY

    Returns:
        bytes: 32 字节密钥，未配置时返回 None
    """
    key_b64 = os.environ.get(env_var)
    if not key_b64:
        logger.warning("加密密钥环境变量 %s 未配置，加密功能不可用", env_var)
        return None

    try:
        key = base64.b64decode(key_b64)
        if len(key) != AES_KEY_SIZE:
            logger.error("加密密钥长度错误: 期望 %d 字节，实际 %d 字节", AES_KEY_SIZE, len(key))
            return None
        return key
    except Exception as e:
        logger.error("加密密钥解码失败: %s", e)
        return None


def generate_key() -> str:
    """
    生成随机 AES-256 密钥（用于初始化配置）

    Returns:
        str: Base64 编码的密钥字符串
    """
    key = secrets.token_bytes(AES_KEY_SIZE)
    return base64.b64encode(key).decode("ascii")


