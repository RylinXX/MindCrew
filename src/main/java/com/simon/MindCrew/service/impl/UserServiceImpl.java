package com.simon.MindCrew.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.simon.MindCrew.common.exception.BusinessException;
import com.simon.MindCrew.common.result.ResultCode;
import com.simon.MindCrew.common.utils.JwtUtils;
import com.simon.MindCrew.entity.SysUser;
import com.simon.MindCrew.entity.dto.LoginDTO;
import com.simon.MindCrew.entity.dto.RegisterDTO;
import com.simon.MindCrew.entity.dto.UserUpdateDTO;
import com.simon.MindCrew.entity.vo.LoginVO;
import com.simon.MindCrew.mapper.SysUserMapper;
import com.simon.MindCrew.entity.dto.ResetPasswordDTO;
import com.simon.MindCrew.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import java.time.LocalDateTime;

/**
 * 用户服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final SysUserMapper sysUserMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtils jwtUtils;
    private final StringRedisTemplate stringRedisTemplate;

    @Value("${upload.path:uploads}")
    private String uploadPath;

    /** Redis key 前缀，TTL 5 分钟 */
    private static final String RESET_CODE_PREFIX = "reset:code:";
    private static final long RESET_CODE_TTL = 5L;

    @Override
    @com.simon.MindCrew.common.audit.Audited(action = "user.login", label = "用户登录", targetType = "user", targetIdParam = "$arg0.username")
    public LoginVO login(LoginDTO dto) {
        SysUser user = sysUserMapper.selectOne(
                new LambdaQueryWrapper<SysUser>()
                        .eq(SysUser::getUsername, dto.getUsername())
                        .eq(SysUser::getDeleted, 0)
        );

        if (user == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }

        if (!passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
            throw new BusinessException(ResultCode.USER_PASSWORD_ERROR);
        }

        if (user.getStatus() == 0) {
            throw new BusinessException(ResultCode.USER_DISABLED);
        }

        // 更新最后登录时间
        user.setLastLogin(LocalDateTime.now());
        sysUserMapper.updateById(user);

        String token = jwtUtils.generateToken(user.getId(), user.getUsername(), user.getRole());

        return LoginVO.builder()
                .token(token)
                .userId(user.getId())
                .username(user.getUsername())
                .nickname(user.getNickname())
                .avatar(user.getAvatar())
                .role(user.getRole())
                .build();
    }

    @Override
    @Transactional
    public void register(RegisterDTO dto) {
        // 检查用户名是否存在
        long usernameCount = sysUserMapper.selectCount(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, dto.getUsername())
        );
        if (usernameCount > 0) {
            throw new BusinessException(ResultCode.USER_ALREADY_EXISTS);
        }

        SysUser user = new SysUser();
        user.setUsername(dto.getUsername());
        user.setPassword(passwordEncoder.encode(dto.getPassword()));
        user.setNickname(StringUtils.isNotBlank(dto.getNickname()) ? dto.getNickname() : dto.getUsername());
        user.setRole("user");
        user.setStatus(1);
        // 任务 7 · 注册时可选填部门 + 职位（null 时为"未分配"，仅能访问 public KB）
        user.setDepartmentId(dto.getDepartmentId());
        user.setPositionId(dto.getPositionId());

        sysUserMapper.insert(user);
        log.info("新用户注册成功: {} (deptId={} posId={})", dto.getUsername(), dto.getDepartmentId(), dto.getPositionId());
    }

    @Override
    public SysUser getCurrentUser() {
        Long userId = getCurrentUserId();
        return sysUserMapper.selectById(userId);
    }

    @Override
    @Transactional
    public void updateUser(UserUpdateDTO dto) {
        Long userId = getCurrentUserId();
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }

        if (StringUtils.isNotBlank(dto.getNickname())) {
            user.setNickname(dto.getNickname());
        }
        if (StringUtils.isNotBlank(dto.getAvatar())) {
            user.setAvatar(dto.getAvatar());
        }
        if (StringUtils.isNotBlank(dto.getPreference())) {
            user.setPreference(dto.getPreference());
        }

        sysUserMapper.updateById(user);
    }

    @Override
    @Transactional
    public void updatePreference(String preference) {
        Long userId = getCurrentUserId();
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }
        user.setPreference(preference);
        sysUserMapper.updateById(user);
    }

    @Override
    public Page<SysUser> listUsers(Integer current, Integer size, String keyword) {
        Page<SysUser> page = new Page<>(current, size);
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getDeleted, 0)
                .orderByDesc(SysUser::getCreateTime);

        if (StringUtils.isNotBlank(keyword)) {
            wrapper.and(w -> w
                    .like(SysUser::getUsername, keyword)
                    .or().like(SysUser::getNickname, keyword)
            );
        }

        return sysUserMapper.selectPage(page, wrapper);
    }

    @Override
    @Transactional
    public void updateUserStatus(Long userId, Integer status) {
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }
        user.setStatus(status);
        sysUserMapper.updateById(user);
    }

    @Override
    @Transactional
    @com.simon.MindCrew.common.audit.Audited(action = "user.role.change", label = "调整用户角色", targetType = "user", targetIdParam = "$arg0")
    public void updateUserRole(Long userId, String role) {
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }
        user.setRole(role);
        sysUserMapper.updateById(user);
    }

    @Override
    @Transactional
    public void updateUserOrg(Long userId, Long departmentId, Long positionId) {
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }
        // null 表示清空（让 mybatis-plus 走 set 而非忽略）
        SysUser patch = new SysUser();
        patch.setId(userId);
        patch.setDepartmentId(departmentId);
        patch.setPositionId(positionId);
        // 用 LambdaUpdateWrapper 强制把 null 字段也写进 SQL（默认 updateById 不会更新 null）
        sysUserMapper.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<SysUser>()
                        .set(SysUser::getDepartmentId, departmentId)
                        .set(SysUser::getPositionId, positionId)
                        .eq(SysUser::getId, userId));
    }

    @Override
    @Transactional
    public String uploadAvatar(MultipartFile file) {
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new BusinessException("只允许上传图片文件");
        }
        if (file.getSize() > 5 * 1024 * 1024) {
            throw new BusinessException("头像图片不能超过 5MB");
        }
        Long userId = getCurrentUserId();
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) throw new BusinessException(ResultCode.USER_NOT_FOUND);

        try {
            String originalName = file.getOriginalFilename();
            String extension = (originalName != null && originalName.contains("."))
                    ? originalName.substring(originalName.lastIndexOf('.'))
                    : ".jpg";
            String filename = UUID.randomUUID() + extension;

            Path dir = Paths.get(uploadPath, "avatar");
            Files.createDirectories(dir);
            Path dest = dir.resolve(filename);
            file.transferTo(dest.toAbsolutePath().toFile());

            String avatarUrl = "/uploads/avatar/" + filename;
            user.setAvatar(avatarUrl);
            sysUserMapper.updateById(user);
            log.info("用户 {} 头像已保存到本地: {}", user.getUsername(), dest.toAbsolutePath());
            return avatarUrl;
        } catch (Exception e) {
            log.error("头像上传失败", e);
            throw new BusinessException("头像上传失败: " + e.getMessage());
        }
    }

    @Override
    public void sendResetCode(String username) {
        SysUser user = sysUserMapper.selectOne(
                new LambdaQueryWrapper<SysUser>()
                        .eq(SysUser::getUsername, username)
                        .eq(SysUser::getDeleted, 0)
        );
        if (user == null) {
            throw new BusinessException("该用户名未注册");
        }

        String key = RESET_CODE_PREFIX + username;
        Long ttl = stringRedisTemplate.getExpire(key, TimeUnit.SECONDS);
        if (ttl != null && ttl > (RESET_CODE_TTL * 60 - 60)) {
            throw new BusinessException("验证码已发送，请60秒后再试");
        }

        String code = String.format("%06d", ThreadLocalRandom.current().nextInt(0, 1000000));
        stringRedisTemplate.opsForValue().set(key, code, RESET_CODE_TTL, TimeUnit.MINUTES);
        log.info("【模拟验证码】用户名: {} 验证码: {} (5分钟内有效)", username, code);
    }

    @Override
    @Transactional
    public void resetPassword(ResetPasswordDTO dto) {
        String key = RESET_CODE_PREFIX + dto.getUsername();
        String savedCode = stringRedisTemplate.opsForValue().get(key);

        if (savedCode == null) {
            throw new BusinessException("验证码已过期，请重新获取");
        }
        if (!savedCode.equals(dto.getCode())) {
            throw new BusinessException("验证码错误，请重新输入");
        }

        SysUser user = sysUserMapper.selectOne(
                new LambdaQueryWrapper<SysUser>()
                        .eq(SysUser::getUsername, dto.getUsername())
                        .eq(SysUser::getDeleted, 0)
        );
        if (user == null) {
            throw new BusinessException("用户不存在");
        }

        user.setPassword(passwordEncoder.encode(dto.getNewPassword()));
        sysUserMapper.updateById(user);
        stringRedisTemplate.delete(key);
        log.info("用户 {} 密码重置成功", user.getUsername());
    }

    @Override
    public Long getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        String username = authentication.getName();
        SysUser user = sysUserMapper.selectOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username)
        );
        if (user == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }
        return user.getId();
    }
}
