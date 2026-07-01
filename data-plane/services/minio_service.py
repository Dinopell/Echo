"""
Project Echo — MinIO 对象存储服务

封装 MinIO 客户端操作，供各 service 模块复用。
所有数据存储在本地 MinIO，不与任何云存储交互。
"""

import io
import logging
import os
import tempfile
from datetime import timedelta
from typing import Optional

from minio import Minio
from minio.error import S3Error

from config import get_settings

logger = logging.getLogger(__name__)


class MinioService:
    """
    MinIO 对象存储服务

    提供：
    - 文件上传（支持流式和字节上传）
    - 文件下载到本地路径
    - 生成预签名 URL（默认 7 天有效）
    - 对象删除
    - Bucket 存在性检查
    """

    # 默认预签名 URL 有效期：7 天
    DEFAULT_PRESIGN_EXPIRES = 7 * 24 * 3600

    def __init__(self):
        self.settings = get_settings()
        self._client: Optional[Minio] = None

    @property
    def client(self) -> Minio:
        """延迟初始化 MinIO 客户端（单例）"""
        if self._client is None:
            self._client = Minio(
                self.settings.minio_endpoint,
                access_key=self.settings.minio_access_key,
                secret_key=self.settings.minio_secret_key,
                secure=self.settings.minio_secure,
            )
        return self._client

    def upload_file(
        self,
        bucket: str,
        object_key: str,
        file_path: str,
        content_type: str = "application/octet-stream",
    ) -> None:
        """
        上传本地文件到 MinIO

        Args:
            bucket: bucket 名称
            object_key: 对象存储路径
            file_path: 本地文件路径
            content_type: MIME 类型
        """
        try:
            self.ensure_bucket_exists(bucket)
            self.client.fput_object(
                bucket,
                object_key,
                file_path,
                content_type=content_type,
            )
            logger.debug("文件上传完成: bucket=%s, key=%s", bucket, object_key)
        except S3Error as e:
            logger.error("上传文件失败: bucket=%s, key=%s, error=%s", bucket, object_key, e)
            raise

    def upload_bytes(
        self,
        bucket: str,
        object_key: str,
        data: bytes,
        content_type: str = "application/octet-stream",
    ) -> None:
        """
        上传字节数据到 MinIO

        Args:
            bucket: bucket 名称
            object_key: 对象存储路径
            data: 字节数据
            content_type: MIME 类型
        """
        try:
            self.ensure_bucket_exists(bucket)
            self.client.put_object(
                bucket,
                object_key,
                io.BytesIO(data),
                length=len(data),
                content_type=content_type,
            )
            logger.debug("上传完成: bucket=%s, key=%s, size=%d", bucket, object_key, len(data))
        except S3Error as e:
            logger.error("上传字节失败: bucket=%s, key=%s, error=%s", bucket, object_key, e)
            raise

    def download_file(
        self,
        bucket: str,
        object_key: str,
        local_path: Optional[str] = None,
        decrypt: bool = False,
    ) -> str:
        """
        从 MinIO 下载文件到本地临时目录

        Args:
            bucket: bucket 名称
            object_key: 对象键
            local_path: 本地保存路径（不指定则自动生成临时文件）
            decrypt: 是否使用 AUDIO_ENCRYPT_KEY 解密（.enc 音频）

        Returns:
            本地文件路径
        """
        if local_path is None:
            # 加密文件解密后使用真实音频后缀
            if ".enc." in object_key:
                suffix = "." + object_key.rsplit(".enc.", 1)[-1]
            else:
                suffix = os.path.splitext(object_key)[1] or ".tmp"
            tmp_fd, local_path = tempfile.mkstemp(suffix=suffix)
            os.close(tmp_fd)

        try:
            if decrypt and (".enc" in object_key or object_key.endswith(".enc")):
                self._download_and_decrypt(bucket, object_key, local_path)
            else:
                self.client.fget_object(bucket, object_key, local_path)
            logger.debug("下载完成: bucket=%s, key=%s -> %s", bucket, object_key, local_path)
            return local_path
        except S3Error as e:
            logger.error("下载文件失败: bucket=%s, key=%s, error=%s", bucket, object_key, e)
            if local_path and os.path.exists(local_path):
                os.remove(local_path)
            raise

    def _download_and_decrypt(self, bucket: str, object_key: str, local_path: str) -> None:
        """下载加密音频并解密到本地文件"""
        import base64
        from utils.crypto import decrypt_audio_bytes

        key_b64 = self.settings.audio_encrypt_key
        if not key_b64:
            raise ValueError("AUDIO_ENCRYPT_KEY 未配置，无法解密音频")

        key = base64.b64decode(key_b64)
        response = self.client.get_object(bucket, object_key)
        try:
            encrypted_bytes = response.read()
        finally:
            response.close()
            response.release_conn()

        plain_bytes = decrypt_audio_bytes(encrypted_bytes, key)
        with open(local_path, "wb") as f:
            f.write(plain_bytes)
        logger.debug("音频解密完成: key=%s, size=%d", object_key, len(plain_bytes))

    def download_to_file(self, bucket: str, object_key: str, local_path: str) -> None:
        """
        从 MinIO 下载文件到本地路径（加密音频自动解密）

        Args:
            bucket: bucket 名称
            object_key: 对象键
            local_path: 本地保存路径
        """
        is_encrypted = ".enc" in object_key
        if is_encrypted:
            self._download_and_decrypt(bucket, object_key, local_path)
        else:
            self.client.fget_object(bucket, object_key, local_path)
        logger.debug("下载完成: bucket=%s, key=%s -> %s", bucket, object_key, local_path)

    def get_presigned_url(
        self,
        bucket: str,
        object_key: str,
        expires_seconds: int = DEFAULT_PRESIGN_EXPIRES,
    ) -> str:
        """
        生成预签名 URL（默认 7 天有效，仅局域网内访问有效）

        Args:
            bucket: bucket 名称
            object_key: 对象键
            expires_seconds: URL 有效期（秒），默认 7 天

        Returns:
            预签名 URL 字符串
        """
        try:
            url = self.client.presigned_get_object(
                bucket, object_key, expires=timedelta(seconds=expires_seconds)
            )
            return url
        except S3Error as e:
            logger.error("生成预签名 URL 失败: bucket=%s, key=%s, error=%s", bucket, object_key, e)
            raise

    def delete_file(self, bucket: str, object_key: str) -> None:
        """
        删除 MinIO 中的对象

        Args:
            bucket: bucket 名称
            object_key: 对象键
        """
        try:
            self.client.remove_object(bucket, object_key)
            logger.info("对象已删除: bucket=%s, key=%s", bucket, object_key)
        except S3Error as e:
            logger.error("删除对象失败: bucket=%s, key=%s, error=%s", bucket, object_key, e)
            raise

    def object_exists(self, bucket: str, object_key: str) -> bool:
        """
        检查对象是否存在

        Args:
            bucket: bucket 名称
            object_key: 对象键

        Returns:
            True 表示存在
        """
        try:
            self.client.stat_object(bucket, object_key)
            return True
        except S3Error:
            return False

    def ensure_bucket_exists(self, bucket: str) -> None:
        """
        确保 bucket 存在（不存在则创建）

        Args:
            bucket: bucket 名称
        """
        try:
            if not self.client.bucket_exists(bucket):
                self.client.make_bucket(bucket)
                logger.info("已创建 MinIO bucket: %s", bucket)
        except S3Error as e:
            logger.error("确保 bucket 存在失败: bucket=%s, error=%s", bucket, e)
            raise

    def init_all_buckets(self) -> None:
        """初始化所有所需 bucket，应用启动时调用"""
        buckets = [
            self.settings.minio_bucket_raw_audio,
            self.settings.minio_bucket_transcriptions,
            self.settings.minio_bucket_memory_cards,
            self.settings.minio_bucket_snapshots,
        ]
        for bucket in buckets:
            self.ensure_bucket_exists(bucket)
        logger.info("所有 MinIO bucket 初始化完成")
