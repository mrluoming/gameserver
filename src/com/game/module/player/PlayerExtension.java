package com.game.module.player;

import com.game.SysConfig;
import com.game.data.Response;
import com.game.event.DefaultLogoutHandler;
import com.game.event.LoginHandler;
import com.game.module.activity.ActivityService;
import com.game.module.admin.ManagerService;
import com.game.module.fashion.FashionService;
import com.game.module.gang.Gang;
import com.game.module.gang.GangService;
import com.game.module.group.GroupService;
import com.game.module.team.Team;
import com.game.module.team.TeamService;
import com.game.module.worldboss.WorldBossService;
import com.game.params.DllversionVO;
import com.game.params.Int2Param;
import com.game.params.IntParam;
import com.game.params.StringParam;
import com.game.params.player.*;
import com.game.params.scene.SkillHurtVO;
import com.game.params.scene.StartSkillVO;
import com.game.sdk.erating.ERatingService;
import com.game.sdk.net.HttpClient;
import com.game.sdk.utils.LuckySdkUtil;
import com.game.util.CommonUtil;
import com.game.util.ConfigData;
import com.game.util.HttpRequestUtil;
import com.google.common.collect.Maps;
import com.server.SessionManager;
import com.server.anotation.Command;
import com.server.anotation.Extension;
import com.server.anotation.UnLogin;
import com.server.util.ServerLogger;
import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

@Extension
public class PlayerExtension {
    @Autowired
    private PlayerService playerService;
    @Autowired
    private DefaultLogoutHandler logoutHandler;
    @Autowired
    private ManagerService managerService;
    @Autowired
    private LoginHandler loginHandler;
    @Autowired
    private GangService gangService;
    @Autowired
    private FashionService fashionService;
    @Autowired
    private ERatingService ratingService;
    @Autowired
    private PlayerCalculator playerCalculator;
    @Autowired
    private GroupService groupService;
    @Autowired
    private TeamService teamService;
    @Autowired
    private ActivityService activityService;

    public static final AttributeKey<String> CHANNEL = AttributeKey.valueOf("channel");
    public static final String IOS = "iOS";

    @UnLogin
    @Command(1001)
    public Object getRoleList(int playerId, CRegRoleListVo param, Channel channel) {
        if (SessionManager.getInstance().getOnlineCount() > SysConfig.maxCon) {
            IntParam result = new IntParam();
            SessionManager.sendDataInner(channel, 1010, result);
            channel.close();
            return null;
        }

        if (IOS.equals(param.platform) && !LuckySdkUtil.checkIosToken(param.token, param.userId)) {
            ServerLogger.warn("登录验证失败，token=" + param.token + " 玩家id=" + param.userId);
            return null;
        }

        List<Player> roleList = playerService.getPlayersByAccName(param.userId);
        ServerLogger.info("获取角色列表");
        channel.attr(CHANNEL).set(param.userId);

        RoleInfoList roleInfoList = new RoleInfoList();
        roleInfoList.roleInfoVoList = new ArrayList<SRoleVo>(roleList.size());

        //账户封禁检测
        if (param.userId != null && managerService.checkBan(param.userId, ManagerService.BAN_LOGIN)) {
            roleInfoList.errorCode = Response.BAN_LOGIN;
            return roleInfoList;
        }

        //IP封禁检测
        if (param.ipAddress != null && managerService.checkBan(param.ipAddress, ManagerService.BAN_IP)) {
            roleInfoList.errorCode = Response.BAN_IP;
            return roleInfoList;
        }

        //设备封禁检测
        if (param.deviceId != null && managerService.checkBan(param.deviceId, ManagerService.BAN_IMEI)) {
            roleInfoList.errorCode = Response.BAN_IMEI;
            return roleInfoList;
        }

        for (Player player : roleList) {
            fashionService.checkRemoveTimeoutFashions(player.getPlayerId(), false);

            SRoleVo role = new SRoleVo();
            role.attack = player.getAttack();
            role.playerId = player.getPlayerId();
            role.crit = player.getCrit();
            role.defense = player.getDefense();
            role.exp = player.getExp();
            role.fashionId = player.getFashionId();
            role.fu = player.getFu();
            role.hp = player.getHp();
            role.lastLoginTime = player.getLastLoginTime() == null ? 0 : player.getLastLoginTime().getTime();
            role.level = player.getLev();
            role.name = player.getName();
            role.sex = player.getSex();
            role.symptom = player.getSymptom();
            role.title = player.getTitle();
            role.vip = player.getVip();
            role.vocation = player.getVocation();
            role.weapon = player.getWeaponId();
            if (player.getGangId() > 0) {
                Gang gang = gangService.getGang(player.getGangId());
                if (gang == null) {
                    player.setGangId(0);
                } else {
                    role.gang = gang.getName();
                }
            }
            role.head = playerService.getPlayerData(player.getPlayerId()).getCurHead();
            roleInfoList.roleInfoVoList.add(role);
        }

        return roleInfoList;
    }

    @UnLogin
    @Command(1002)
    public Object createRole(int playerId, CRegVo param, Channel channel) {
        // 连接数太多
        RegResultVo result = new RegResultVo();
        if (SessionManager.getInstance().getOnlineCount() > SysConfig.maxCon) {
            result.code = Response.TOO_MANY_CON;
            return result;
        }
        // 关闭注册
        if (!SysConfig.reg) {
            result.code = Response.CLOSE_REG;
            return result;
        }
        // 版本检测
        if (!playerService.checkVersion(param.version)) {
            result.code = Response.LOW_VERSION;
            return result;
        }

        // 登录验证
        int auth = playerService.auth();
        if (auth != 0) {
            result.code = auth;
            return result;
        }
        // 参数错误
        if (!playerService.checkRegParam(param)) {
            result.code = Response.ERR_PARAM;
            return result;
        }
        // 同名
        if (playerService.getPlayerIdByName(param.name) > 0) {
            result.code = Response.SAME_NAME;
            return result;
        }
        // 角色数量上限
        if (playerService.getPlayersByAccName(param.accName).size() >= ConfigData.globalParam().maxRoleCount) {
            result.code = Response.TOO_MANY_ROLE;
            return result;
        }
        Player player = playerService.addNewPlayer(param.name, param.sex, param.vocation, param.accName, param.channel, param.serverId, param.serverName, param.userId, param.thirdChannel, param.thirdUserId, param.clientMac);
        if (player == null) {
            result.code = Response.SAME_NAME;
            return result;
        }

        CLoginVo loginVo = new CLoginVo();
        loginVo.playerId = player.getPlayerId();
        loginVo.version = ConfigData.globalParam().version;// 版本
        loginVo.clientMac = param.clientMac;
        loginVo.clientType = param.clientType;
        loginVo.hardwarSn1 = param.hardwarSn1;
        loginVo.hardwarSn2 = param.hardwarSn2;
        loginVo.modelVersion = param.modelVersion;
        loginVo.ldid = param.ldid;
        loginVo.uddi = param.uddi;
        loginVo.un = param.un;

        result.serverId = String.valueOf(param.serverId);
        result.serverName = param.serverName;
        result.userName = param.accName;
        result.roleId = String.valueOf(player.getPlayerId());
        result.roleName = player.getName();
        result.createTime = String.valueOf(player.getRegTime().getTime() / 1000);
        result.roleCareer = player.getVocation();
        result.gatewayId = SysConfig.gatewayId;

        String host = channel.remoteAddress().toString();
        String[] arr = host.split(":");
        player.clientPort = Integer.parseInt(arr[1]);
        String[] hostArr = arr[0].substring(1).split("\\.");
//        player.clientIp = Integer.parseInt(hostArr[0]) * 2563 + Integer.parseInt(hostArr[1]) * 2562 + Integer.parseInt(hostArr[2]) * 256 + Integer.parseInt(hostArr[3]);
        player.clientIp = (int) (Math.pow(Integer.parseInt(hostArr[0]) * 256, 3) + Math.pow(Integer.parseInt(hostArr[1]) * 256, 2) + Integer.parseInt(hostArr[2]) * 256 + Integer.parseInt(hostArr[3]));
        //player.userId = param.userId;


        // 直接返回登录结果
        loginVo.userId = player.userId;
        PlayerVo loginResult = login(0, loginVo, channel);
        SessionManager.sendDataInner(channel, 1003, loginResult);

        //report create log
        ratingService.reportCreateRole(player);
        player.bCrateRole = true;
        return result;
    }

    @UnLogin
    @Command(1003)
    public PlayerVo login(int id, CLoginVo param, Channel channel) {
        PlayerVo result = new PlayerVo();
        if (param.playerId == 0) {

            result.code = Response.ERR_PARAM;
            return result;
        }
        // 版本检测
        if (!playerService.checkVersion(param.version)) {
            result.code = Response.LOW_VERSION;
            return result;
        }

        // 连接数太多
        if (SessionManager.getInstance().getOnlineCount() > SysConfig.maxCon) {
            result.code = Response.TOO_MANY_CON;
            return result;
        }

        // 登录验证
        int auth = playerService.auth();
        if (auth != 0) {
            result.code = auth;
            return result;
        }
        // 踢走旧的登录
        int playerId = param.playerId;
        Player player = playerService.getPlayer(playerId);
        String accName = channel.attr(CHANNEL).get();
        if (accName == null || !accName.equals(player.getAccName())) {
            result.code = Response.ERR_PARAM;
            return result;
        }
        //final Channel oldChannel = SessionManager.getInstance().getChannel(playerId);
        final User user = playerService.getOldAndCache(accName, playerId, channel);
        if (user != null) {
            ServerLogger.debug("duplicated login:", user.playerId);
            SessionManager.getInstance().removePlayerAttr(user.channel);
            IntParam param1 = new IntParam();
            param1.param = Response.RE_LOGIN;
            SessionManager.sendDataInner(user.channel, 1015, param1);
            user.channel.close();
            logoutHandler.logout(user.playerId, channel);
        }

        player.setRefresh(false);
        player.setSubLine(0);
        SessionManager.getInstance().addSession(playerId, channel);
        // 第一次登录
        if (player.getLastLoginTime() == null) {
            playerService.handleFirstLogin(playerId);
            playerService.addLoginCount(playerId);//增加每日登录人数
        }
        player.setLastLoginTime(new Date());
        player.onlineTime = System.currentTimeMillis();

        player.setIp(CommonUtil.getIp(channel.remoteAddress()));
        // 处理登录
        playerService.handleLogin(playerId);
        player.setLastLogoutTime(new Date());
        //其它子系统的登录处理
        loginHandler.playerLogin(playerId);

        //刷新属性
        playerCalculator.calculate(playerId);

        result = playerService.toSLoginVo(playerId);

        PlayerData data = playerService.getPlayerData(playerId);
        result.userName = accName;
        result.serverName = data.getServerName();
        result.serverId = player.getServerId();
        player.setRefresh(true);

        player.clientType = param.clientType == 0 ? 3 : param.clientType;
        player.clientMac = param.clientMac == null ? "" : param.clientMac;
        player.hardwarSn1 = param.hardwarSn1 == null ? "" : param.hardwarSn1;
        player.hardwarSn2 = param.hardwarSn2 == null ? "" : param.hardwarSn2;
        player.uddi = param.uddi == null ? "" : param.uddi;
        player.modelVersion = param.modelVersion == null ? "" : param.modelVersion;
        player.ldid = param.ldid == null ? "" : param.ldid;
        player.adid = "1";
        player.token = param.token;
        player.userId = param.userId;

        try {
            String host = channel.remoteAddress().toString();
            String[] arr = host.split(":");
            player.clientPort = Integer.parseInt(arr[1]);
            String[] hostArr = arr[0].substring(1).split("\\.");
//            player.clientIp = Integer.parseInt(hostArr[0]) * 2563 + Integer.parseInt(hostArr[1]) * 2562 + Integer.parseInt(hostArr[2]) * 256 + Integer.parseInt(hostArr[3]);
            player.clientIp = (int) (Math.pow(Integer.parseInt(hostArr[0]) * 256, 3) + Math.pow(Integer.parseInt(hostArr[1]) * 256, 2) + Integer.parseInt(hostArr[2]) * 256 + Integer.parseInt(hostArr[3]));
        } catch (Exception e) {
            ServerLogger.err(e, "ip 解析失败");
        }

        if (!player.bCrateRole) {
            ratingService.reportRoleEnter(player, data.getRoleId());
        }
        // 设置session等级
        SessionManager.getInstance().setPlayerLev(playerId, player.getLev());
        ServerLogger.info("user login:" + playerId + " 设备mac:" + param.clientMac);
        //System.out.println("=============" + playerService.getPlayers().size());

        //重置活动
        activityService.ResetActivityTask(playerId);

        return result;
    }

    @UnLogin
    @Command(10801)
    public Object CheckDllMd5Version(int playerId, DllversionVO param, Channel channel)
    {
        String cs = "Assembly-CSharp.dll";
        String cs_first = "Assembly-CSharp-firstpass.dll";
        // 只有android才会发 10801
        if (!dllMd5Map.containsKey(param.group) || !dllMd5Map.get(param.group).containsKey(param.version))
        {
            String url =String.format("http://got.cdn-down.kokoyou.com/game2/ingcle/%s/android_res/%s/resfiles/dll_apk/dllsversion", param.group, param.version);
            try {
                String content = HttpClient.sendGetRequest(url);

                if (content == null || content.isEmpty()) {
                    return  null;
                }

                if (content.contains("404 Not Found")) {
                    ServerLogger.info("CheckDllMd5Version: 404 Not Found :" + playerId + " url:" + url);
                    IntParam result = new IntParam();
                    result.param = 0;
                    return result;
                }

                String[] lines = content.split("\\r?\\n");
                if (lines.length == 2) {

                    Map<String, String> md5s = Maps.newConcurrentMap();
                    for (int i = 0; i < lines.length; ++i) {
                        String[] parts = lines[i].split(",");
                        if (parts.length >= 3) {
                            md5s.put(parts[0], parts[2]);
                        }
                    }
                    if (md5s.containsKey(cs) && md5s.containsKey(cs_first)) {
                        if (!dllMd5Map.containsKey(param.group)) {
                            dllMd5Map.put(param.group, Maps.newConcurrentMap());
                        }

                        dllMd5Map.get(param.group).put(param.version, md5s);
                    }
                }
            } catch (Exception e) {
                ServerLogger.err(e, "url=" + url);
                return  null;
            }
        }

        String file1 = dllMd5Map.get(param.group).get(param.version).get(cs);
        String file2 = dllMd5Map.get(param.group).get(param.version).get(cs_first);
        if (file1.equals(param.file1) && file2.equals(param.file2))
        {
            return  null;
        }
        else
        {
            ServerLogger.info("CheckDllMd5Version: 不匹配dll版本，有可能作弊 :" + playerId + ",param.group=" + param.group + ",param.version=" + param.version + ",Client: Assembly-CSharp.dll:" + param.file1 + " Assembly-CSharp-firstpass.dll:" + param.file2 + " Server: Assembly-CSharp.dll:" + file1 + " Assembly-CSharp-firstpass.dll:" + file2);
            IntParam result = new IntParam();
            result.param = 0;
            return result;
        }
    }

    // 更新玩家属性
    public static final int REFRESH_MY_VO = 1004;

    // 属性更新n条属性
    public static final int UPDATE_ATTR = 1005;

    //更新货币
    public static final int UPDATE_CURRENCY = 1006;

    // <group, version, <file, md5>
    private static Map<String, Map<String, Map<String, String>>> dllMd5Map = Maps.newConcurrentMap();

    @Command(1007)
    public Object getOtherPlayer(int playerId, IntParam param) {
        return playerService.toSLoginVo(param.param);
    }

    @Command(1008)
    public Object openModule(int playerId, IntParam param) {
        return playerService.moduleOpen(playerId, param.param);
    }

    @Command(1013)
    public Object hitModule(int playerId, Int2Param type) {
        return playerService.hitModule(playerId, type.param1, type.param2);
    }

    @Command(1014)
    public Object getModules(int playerId, Object param) {
        return playerService.getModule(playerId);
    }

    @Command(10502)
    public Object actionModule(int playerId, IntParam param) {
        playerService.actionModule(playerId, param.param);
        return null;
    }

    @Command(10501)
    public Object getActionModule(int playerId, Object param) {
        return playerService.getActionModule(playerId);
    }

    @Command(1009)
    public Object newHandleStep(int playerId, IntParam param) {
        PlayerData playerData = playerService.getPlayerData(playerId);
        playerData.getGuideSteps().add(param.param);
        IntParam intParam = new IntParam();
        intParam.param = param.param;
        return intParam;
    }

    @Command(1011)
    public Object selectRole(int playerId, Object param) {
        playerService.selectRole(playerId);
        return null;
    }

    @UnLogin
    @Command(1012)
    public Object userAuth(int playerId, UserAuth param, Channel channel) {
        String host = channel.remoteAddress().toString();
        String[] arr = host.split(":");
        int clientPort = Integer.parseInt(arr[1]);
        String hostIp = arr[0].substring(1).trim();
        Date date = new Date();
        if (date.before(SysConfig.openDate)) { //还没到开服时间
            ServerLogger.warn("client ip = " + hostIp);
            if (!ConfigData.accountSet.contains(hostIp)) { //非白名单
                ServerLogger.warn("client ip ---------- " + hostIp);
                IntParam param1 = new IntParam();
                param1.param = -5;
                return param1;
            }
        }
        String[] hostArr = hostIp.split("\\.");
//        int clientIp = Integer.parseInt(hostArr[0]) * 2563 + Integer.parseInt(hostArr[1]) * 2562 + Integer.parseInt(hostArr[2]) * 256 + Integer.parseInt(hostArr[3]);
        int clientIp = (int) (Math.pow(Integer.parseInt(hostArr[0]) * 256, 3) + Math.pow(Integer.parseInt(hostArr[1]) * 256, 2) + Integer.parseInt(hostArr[2]) * 256 + Integer.parseInt(hostArr[3]));
        ratingService.reportAuthen(param.un, param.token, clientIp, clientPort, param.clientMac, param.clientType, param.modelVersion, channel);
        return null;
    }

    @Command(10101)
    public Object feedback(int playerId, StringParam param) {
        IntParam result = new IntParam();
        result.param = Response.SUCCESS;
        return result;
    }

    @Command(1016)
    public Object updateName(int playerId, StringParam stringParam) {
        IntParam result = new IntParam();
        String name = stringParam.param;
        // 同名
        if (playerService.getPlayerIdByName(name) > 0) {
            result.param = Response.SAME_NAME;
            return result;
        }

        playerService.updatePlayerName(playerId, name);

        return result;
    }

    @Command(10802)
    public Object CheckStartSkill(int playerId, StartSkillVO param)
    {
        Player player = playerService.getPlayer(playerId);
        if (!CheatReventionService.isValidSkillHurt(playerId, param.skillId, player.getVocation())) {
            ServerLogger.info("CheckStartSkill isValidSkillHurt 作弊玩家 Id = " + player.getPlayerId() + " skillId=" + param.skillId + " skillCD=" + param.skillCD);
            SessionManager.getInstance().kick(playerId);
        }
        return null;
    }

    @Command(10803)
    public Object CheckSkillHurt(int playerId, SkillHurtVO param)
    {
        Player player = playerService.getPlayer(playerId);
        if (!player.checkHurt(param.hurtValue, 2)) {
            // 超过1.5倍的最大范围值，肯定是作弊了
            ServerLogger.info("CheckSkillHurt 作弊:单次伤害超过2倍最大伤害范围，作弊玩家=" + playerId + " harm=" + param.hurtValue);
            SessionManager.getInstance().kick(playerId);
        }
        else {
            CheatReventionService.addPlayerHurtRecord(playerId);
        }
        return null;
    }

    //踢人下线
    public static final int FORCE_LOGOUT = 1017;
}
