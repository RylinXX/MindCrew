package com.simon.MindCrew.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.simon.MindCrew.entity.SysUser;
import com.simon.MindCrew.entity.dto.LoginDTO;
import com.simon.MindCrew.entity.dto.RegisterDTO;
import com.simon.MindCrew.entity.dto.UserUpdateDTO;
import com.simon.MindCrew.entity.vo.LoginVO;

/**
 * 用户服务接口
 */
public interface UserService {

    /**
     * 用户登录
     */
    LoginVO login(LoginDTO dto);

    /**
     * 用户注册
     */
    void register(RegisterDTO dto);

    /**
     * 获取当前用户信息
     */
    SysUser getCurrentUser();

    /**
     * 更新用户信息
     */
    void updateUser(UserUpdateDTO dto);

    /**
     * 更新用户偏好
     */
    void updatePreference(String preference);

    /**
     * 分页查询用户列表 (管理员)
     */
    Page<SysUser> listUsers(Integer current, Integer size, String keyword);

    /**
     * 修改用户状态 (管理员)
     */
    void updateUserStatus(Long userId, Integer status);

    /**
     * 修改用户角色 (管理员)
     */
    void updateUserRole(Long userId, String role);

    /**
     * 任务 7 · 给用户分配部门 + 职位（管理员）
     * 任一参数为 null 表示清空对应字段
     */
    void updateUserOrg(Long userId, Long departmentId, Long positionId);

    /**
     * 上传头像
     */
    String uploadAvatar(org.springframework.web.multipart.MultipartFile file);

    /**
     * 发送找回密码验证码（模拟短信，实际打印到日志）
     */
    void sendResetCode(String phone);

    /**
     * 校验验证码并重置密码
     */
    void resetPassword(com.simon.MindCrew.entity.dto.ResetPasswordDTO dto);

    /**
     * 获取当前登录用户ID
     */
    Long getCurrentUserId();
}
