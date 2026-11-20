package com.pp5rd.status

import com.google.gson.annotations.SerializedName

// Type aliases para compatibilidade com ApiClient
typealias StatsResponse = ServerStats
typealias PistarResponse = PiStarData

data class ServerStats(
    val ts: Long = 0,
    val cpu: Int = 0,
    val cores: Int = 1,
    val load1: Double = 0.0,
    val temp: Int = 0,
    @SerializedName("mem_total") val memTotal: Double = 0.0,
    @SerializedName("mem_used") val memUsed: Double = 0.0,
    @SerializedName("mem_pct") val memPct: Int = 0,
    @SerializedName("disk_total") val diskTotal: Int = 0,
    @SerializedName("disk_used") val diskUsed: Int = 0,
    @SerializedName("disk_pct") val diskPct: Int = 0,
    @SerializedName("up_d") val upDays: Int = 0,
    @SerializedName("up_h") val upHours: Int = 0,
    @SerializedName("up_m") val upMinutes: Int = 0,
    @SerializedName("rx_tot") val rxTot: Double = 0.0,
    @SerializedName("tx_tot") val txTot: Double = 0.0,
    @SerializedName("rx_day") val rxDay: Double = 0.0,
    @SerializedName("tx_day") val txDay: Double = 0.0,
    @SerializedName("net_rx") val netRx: Int = 0,
    @SerializedName("net_tx") val netTx: Int = 0,
    val conns: Int = 0,
    val cont: ContainerStats? = null,
    @SerializedName("cont_stats") val contStats: Map<String, ContainerUsage>? = null,
    val svc: Map<String, Int>? = null,
    val tetra: TetraStats? = null,
    val ebooks: Int = 0,
    val reading: ReadingStats? = null,
    val site: SiteStats? = null,
    val wifi: WifiStats? = null,
    val tunnel: TunnelStats? = null,
    val tunnels: List<CloudflareTunnel>? = null,
    val topcpu: String = "",
    @SerializedName("topcpu_pct") val topcpuPct: Double = 0.0,
    @SerializedName("docker_mem") val dockerMem: Int = 0
)

data class ContainerUsage(
    val cpu: Int = 0,
    val mem: Int = 0
)

data class WifiStats(
    val ssid: String = "",
    val chan: Int = 0,
    val signal: Int = 0,
    val speed: Int = 0,
    val qual: Int = 0
)

data class TunnelStats(
    val up: Int = 0,
    val uptime: Int = 0
)

data class CloudflareTunnel(
    val name: String = "",
    val status: String = "",
    val uptime: Int = 0,
    val routes: List<String> = emptyList()
)

data class ContainerStats(
    val up: Int = 0,
    val total: Int = 0,
    val telive: Int = 0,
    val icecast: Int = 0,
    val calibre: Int = 0,
    val koreader: Int = 0,
    val openwebrx: Int = 0
)

data class TetraStats(
    val sdr: Int = 0,
    val listeners: Int = 0,
    val crc2: Int = 0,
    val t1: Int = 0,
    val t2: Int = 0,
    val t3: Int = 0,
    val t4: Int = 0,
    val a1: Int = 0,
    val a2: Int = 0,
    val a3: Int = 0,
    val a4: Int = 0,
    val l1: Int = 9999,
    val l2: Int = 9999,
    val l3: Int = 9999,
    val l4: Int = 9999
)

data class ReadingStats(
    val title: String = "",
    val author: String = "",
    val pct: Int = 0,
    val device: String = "",
    val cover: String = "",
    val recent: List<RecentBook> = emptyList()
)

data class RecentBook(
    val title: String = "",
    val author: String = "",
    val pct: Int = 0
)

data class SiteStats(
    val req: Int = 0,
    @SerializedName("ok_pct") val okPct: Int = 0,
    val conc: Int = 0,
    val visits: Int = 0
)

data class PiStarData(
    val ts: Long = 0,
    val ok: Boolean = false,
    val active: Boolean = false,
    val cur: PiStarEntry? = null,
    val last: List<PiStarEntry> = emptyList(),
    val today: Int = 0,
    val countries: Int = 0
)

data class PiStarEntry(
    val call: String = "",
    val name: String = "",
    val cc: String = "",
    val country: String = "",
    val tg: String = "",
    val mode: String = "",
    val slot: String = "",
    val src: String = "",
    val dur: String = "",
    val ber: String = "",
    val start: Long = 0,
    val active: Boolean = false
)
