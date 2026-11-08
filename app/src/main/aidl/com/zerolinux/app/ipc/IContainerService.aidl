package com.zerolinux.app.ipc;

interface IContainerService {
    String getApiVersion();
    int getContainerCount();
    String getActiveDistro();

    /** 全量容器配置的 JSON 数组，每项与 container.json 同结构。 */
    String listContainersJson();

    /** 当前活动容器的 JSON，无容器时返回空串。 */
    String getActiveContainerJson();
}
