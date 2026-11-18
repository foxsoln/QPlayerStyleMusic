/* This is YOUR custom .mplug v1 plugin, not QPlayer's .qplug format.
 * getInfo / search are synchronous; native HTTP is intentionally unavailable in V0.6.
 */
globalThis.Mplug = {
  getInfo: function () {
    return {
      source: "demo.local",
      name: "离线测试音源",
      version: "1.0.0",
      message: "QuickJS 已成功执行自定义 .mplug！"
    };
  },
  search: function (params) {
    var keyword = String(params.keyword || "测试");
    return [
      { id: "demo:song:001", title: keyword + " - 测试曲目一", artist: "示例歌手", playable: false },
      { id: "demo:song:002", title: keyword + " - 测试曲目二", artist: "示例歌手", playable: false }
    ];
  }
};
