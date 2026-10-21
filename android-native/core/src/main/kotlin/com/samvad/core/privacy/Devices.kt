package com.samvad.core.privacy

/** One of the account's installs (users/{uid}/devices/{id}), for the Linked devices screen. */
data class LinkedDevice(
  val id: String,
  val platform: String,
  val appVersion: String,
  val osVersion: String,
  val createdAtMs: Long,
  val lastActiveMs: Long,
  val isThisDevice: Boolean,
)

object LinkedDevices {
  /** This device first, then the others by most recently active. */
  fun sorted(devices: List<LinkedDevice>): List<LinkedDevice> =
    devices.sortedWith(compareByDescending<LinkedDevice> { it.isThisDevice }.thenByDescending { it.lastActiveMs })
}
