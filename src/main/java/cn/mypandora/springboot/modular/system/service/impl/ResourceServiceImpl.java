package cn.mypandora.springboot.modular.system.service.impl;

import cn.mypandora.springboot.core.enums.ResourceTypeEnum;
import cn.mypandora.springboot.core.enums.StatusEnum;
import cn.mypandora.springboot.core.exception.CustomException;
import cn.mypandora.springboot.core.shiro.rule.RolePermRule;
import cn.mypandora.springboot.modular.system.mapper.ResourceMapper;
import cn.mypandora.springboot.modular.system.mapper.RoleMapper;
import cn.mypandora.springboot.modular.system.mapper.RoleResourceMapper;
import cn.mypandora.springboot.modular.system.model.po.BaseEntity;
import cn.mypandora.springboot.modular.system.model.po.Resource;
import cn.mypandora.springboot.modular.system.model.po.Role;
import cn.mypandora.springboot.modular.system.service.ResourceService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tk.mybatis.mapper.entity.Example;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * ResourceServiceImpl
 *
 * @author hankaibo
 * @date 2019/1/15
 * @see <a href="http://www.spoofer.top/2017/07/14/%E5%9F%BA%E4%BA%8ENested-Sets%E7%9A%84%E6%A0%91%E5%BD%A2%E6%95%B0%E6%8D%AE%E5%BA%93%E8%AF%A6%E7%BB%86%E8%AE%BE%E8%AE%A1">左右节点树操作</a>
 */
@Service
public class ResourceServiceImpl implements ResourceService {

    private ResourceMapper resourceMapper;
    private RoleResourceMapper roleResourceMapper;
    private RoleMapper roleMapper;

    @Autowired
    public ResourceServiceImpl(ResourceMapper resourceMapper, RoleResourceMapper roleResourceMapper, RoleMapper roleMapper) {
        this.resourceMapper = resourceMapper;
        this.roleResourceMapper = roleResourceMapper;
        this.roleMapper = roleMapper;
    }

    @Override
    public List<RolePermRule> listRolePermRules() {
        List<RolePermRule> rolePermRuleList = new ArrayList<>();
        List<Resource> resourceList = resourceMapper.listRolePermRules();
        for (Resource resource : resourceList) {
            RolePermRule rolePermRule = new RolePermRule();
            rolePermRule.setUrl(resource.getUri());
            rolePermRule.setNeedRoles(resource.getNeedRoles());
            rolePermRuleList.add(rolePermRule);
        }
        return rolePermRuleList;
    }

    @Override
    public List<Resource> listResource(Integer type, Integer status, Long userId) {
        return resourceMapper.listByUserIdOrName(userId, null, type, status);
    }

    @Override
    public List<Resource> listChildrenResource(Long id, Integer type, Integer status, Long userId) {
        List<Resource> allResourceList = listResource(type, status, userId);

        List<Resource> resourceList = resourceMapper.listChildren(id, type, status);
        return resourceList.stream().filter(allResourceList::contains).collect(Collectors.toList());
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void addResource(Resource resource, Long userId) {
        List<Resource> resourceList = listResource(ResourceTypeEnum.MENU.getValue(), StatusEnum.ENABLED.getValue(), userId);
        if (resourceList.stream().noneMatch(item -> item.getId().equals(resource.getParentId()))) {
            throw new CustomException(HttpStatus.BAD_REQUEST.value(), "父级资源选择错误。");
        }

        Long parentId = resource.getParentId();
        Resource parentResource = getResourceById(parentId, userId);
        LocalDateTime now = LocalDateTime.now();
        resource.setCreateTime(now);
        resource.setLft(parentResource.getRgt());
        resource.setRgt(parentResource.getRgt() + 1);
        resource.setLevel(parentResource.getLevel() + 1);
        resource.setIsUpdate(1);

        int amount = 2;
        resourceMapper.lftAdd(parentId, amount, null);
        resourceMapper.rgtAdd(parentId, amount, null);
        resourceMapper.insert(resource);
        resourceMapper.parentRgtAdd(parentId, amount);
        // 所有资源默认添加到根角色
        Role role = getRootRole();
        long[] plusId = {resource.getId()};
        roleResourceMapper.grantRoleResource(role.getId(), plusId);
    }

    @Override
    public Resource getResourceById(Long id, Long userId) {
        List<Resource> resourceList = listResource(null, StatusEnum.ENABLED.getValue(), userId);
        if (resourceList.stream().noneMatch(item -> item.getId().equals(id))) {
            throw new CustomException(HttpStatus.BAD_REQUEST.value(), "无法查看该资源。");
        }

        Resource resource = new Resource();
        resource.setId(id);
        Resource info = resourceMapper.selectByPrimaryKey(resource);
        if (info == null) {
            throw new CustomException(HttpStatus.NOT_FOUND.value(), "资源不存在。");
        }
        return info;
    }

    @Override
    public void updateResource(Resource resource, Long userId) {
        List<Resource> resourceList = listResource(ResourceTypeEnum.MENU.getValue(), StatusEnum.ENABLED.getValue(), userId);
        if (resourceList.stream().noneMatch(item -> item.getId().equals(resource.getParentId()))) {
            throw new CustomException(HttpStatus.BAD_REQUEST.value(), "父级资源错误。");
        }

        if (!isCanUpdateParent(resource)) {
            throw new CustomException(HttpStatus.BAD_REQUEST.value(), "不可以选择子资源作为自己的父级。");
        }

        if (resourceList.stream().noneMatch(item -> item.getId().equals(resource.getId()))) {
            throw new CustomException(HttpStatus.BAD_REQUEST.value(), "资源错误。");
        }

        Resource info = getResourceById(resource.getId(), userId);

        if (!info.getParentId().equals(resource.getParentId())) {
            Resource newParentResource = getResourceById(resource.getParentId(), userId);
            Resource oldParentResource = getResourceById(info.getParentId(), userId);
            Resource commonAncestry = getCommonAncestry(newParentResource, oldParentResource);

            List<Long> updateIdList = listDescendantId(resource.getId());
            int departmentNum = updateIdList.size();

            resourceMapper.locking(updateIdList, 0);

            Long oldId = info.getId();
            int oldAmount = departmentNum * -2;
            int oldRange = commonAncestry.getRgt();
            resourceMapper.lftAdd(oldId, oldAmount, oldRange);
            resourceMapper.rgtAdd(oldId, oldAmount, oldRange);

            Long newId = newParentResource.getId();
            int newAmount = departmentNum * 2;
            int newRange = commonAncestry.getRgt();
            resourceMapper.lftAdd(newId, newAmount, newRange);
            resourceMapper.rgtAdd(newId, newAmount, newRange);
            resourceMapper.parentRgtAdd(newId, newAmount);

            resourceMapper.locking(updateIdList, 1);
            int amount = getResourceById(resource.getParentId(), userId).getRgt() - info.getRgt() - 1;
            int level = newParentResource.getLevel() + 1 - info.getLevel();
            resourceMapper.selfAndDescendant(updateIdList, amount, level);

            LocalDateTime now = LocalDateTime.now();
            resource.setUpdateTime(now);
            resource.setLevel(newParentResource.getLevel() + 1);
        }
        resourceMapper.updateByPrimaryKeySelective(resource);
    }

    @Override
    public void enableResource(Long id, Integer type, Integer status, Long userId) {
        List<Resource> resourceList = listResource(type, status, userId);
        List<Long> idList = listDescendantId(id);
        List<Long> allIdList = resourceList.stream().map(BaseEntity::getId).collect(Collectors.toList());
        if (!allIdList.retainAll(idList)) {
            throw new CustomException(HttpStatus.BAD_REQUEST.value(), "资源错误。");
        }
        resourceMapper.enableDescendants(idList, status);
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void deleteResource(Long id, Long userId) {
        List<Resource> resourceList = listResource(null, null, userId);
        List<Long> allIdList = resourceList.stream().map(BaseEntity::getId).collect(Collectors.toList());
        if (!allIdList.contains(id)) {
            throw new CustomException(HttpStatus.BAD_REQUEST.value(), "资源错误。");
        }

        Resource resource = new Resource();
        resource.setId(id);

        Resource info = resourceMapper.selectByPrimaryKey(resource);
        int deleteAmount = info.getRgt() - info.getLft() + 1;

        resourceMapper.lftAdd(id, -deleteAmount, null);
        resourceMapper.rgtAdd(id, -deleteAmount, null);
        // 求出要删除的资源所有子孙资源的id
        List<Long> idList = listDescendantId(id);
        String ids = StringUtils.join(idList, ',');
        // 批量删除资源及子孙资源
        resourceMapper.deleteByIds(ids);
        // 批量删除资源及子孙资源对应的角色数据
        roleResourceMapper.deleteBatchResourceAllRole(idList.stream().distinct().mapToLong(it -> it).toArray());
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void moveResource(Long sourceId, Long targetId, Long userId) {
        List<Resource> resourceList = listResource(1, null, userId);
        List<Long> idList = new ArrayList<>();
        idList.add(sourceId);
        idList.add(targetId);
        List<Long> allIdList = resourceList.stream().map(BaseEntity::getId).collect(Collectors.toList());
        if (!allIdList.retainAll(idList)) {
            throw new CustomException(HttpStatus.BAD_REQUEST.value(), "资源错误。");
        }

        Resource sourceResource = new Resource();
        Resource targetResource = new Resource();

        sourceResource.setId(sourceId);
        targetResource.setId(targetId);

        Resource sourceInfo = resourceMapper.selectByPrimaryKey(sourceResource);
        Resource targetInfo = resourceMapper.selectByPrimaryKey(targetResource);

        int sourceAmount = sourceInfo.getRgt() - sourceInfo.getLft() + 1;
        int targetAmount = targetInfo.getRgt() - targetInfo.getLft() + 1;

        List<Long> sourceIdList = listDescendantId(sourceId);
        List<Long> targetIdList = listDescendantId(targetId);

        // 确定方向，目标大于源，下移；反之，上移。
        if (targetInfo.getRgt() > sourceInfo.getRgt()) {
            sourceAmount *= -1;
        } else {
            targetAmount *= -1;
        }
        resourceMapper.selfAndDescendant(sourceIdList, targetAmount, 0);

        resourceMapper.selfAndDescendant(targetIdList, sourceAmount, 0);
    }

    @Override
    public List<Resource> listResourceByRoleIds(Long[] roleIds, Integer type, Integer status, Long userId) {
        List<Resource> allResourceList = listResource(type, status, userId);

        List<Resource> resourceList = resourceMapper.listByRoleIds(roleIds, type, status);
        return resourceList.stream().filter(allResourceList::contains).collect(Collectors.toList());
    }

    @Override
    public List<Resource> listResourceByUserIdOrName(Long userId, String username, Integer type, Integer status) {
        return resourceMapper.listByUserIdOrName(userId, username, type, status);
    }

    /**
     * 获取此资源及其子孙资源的id。
     *
     * @param id 资源
     * @return 资源主键id集合
     */
    private List<Long> listDescendantId(Long id) {
        List<Resource> resourceList = resourceMapper.listDescendants(id, null, null);
        List<Long> idList = resourceList.stream().map(BaseEntity::getId).collect(Collectors.toList());
        idList.add(id);
        return idList;
    }

    /**
     * 防止更新资源时，指定自己的下级资源作为自己的父级资源。
     *
     * @param resource 资源
     * @return true可以更新；false不可以更新
     */
    private boolean isCanUpdateParent(Resource resource) {
        Resource child = new Resource();
        child.setId(resource.getId());
        Resource childResource = resourceMapper.selectByPrimaryKey(child);

        Resource parent = new Resource();
        parent.setId(resource.getParentId());
        Resource parentResource = resourceMapper.selectByPrimaryKey(parent);
        return !(parentResource.getLft() >= childResource.getLft() && parentResource.getRgt() <= childResource.getRgt());
    }

    /**
     * 获取两个资源最近的共同祖先资源。
     *
     * @param resource1 第一个资源
     * @param resource2 第二个资源
     * @return 最近的祖先资源
     */
    private Resource getCommonAncestry(Resource resource1, Resource resource2) {
        // 首先判断两者是否是包含关系
        if(resource1.getLft()<resource2.getLft() && resource1.getRgt()>resource2.getRgt()){
            return resource1;
        }
        if(resource2.getLft()<resource1.getLft() && resource2.getRgt()>resource1.getRgt()){
            return resource2;
        }
        // 两者没有包含关系的情况下
        Long newId = resource1.getId();
        List<Resource> newParentAncestries = resourceMapper.listAncestries(newId, null, StatusEnum.ENABLED.getValue());
        if (newParentAncestries.size() == 0) {
            newParentAncestries.add(resource1);
        }

        Long oldId = resource2.getId();
        List<Resource> oldParentAncestries = resourceMapper.listAncestries(oldId, null, StatusEnum.ENABLED.getValue());
        if (oldParentAncestries.size() == 0) {
            oldParentAncestries.add(resource2);
        }

        Comparator<Resource> comparator = Comparator.comparing(Resource::getLft);
        return newParentAncestries.stream().filter(oldParentAncestries::contains).max(comparator).orElseThrow(RuntimeException::new);
    }

    /**
     * 得到根角色
     * <p>
     * 根角色特征：层级level为1，父级parentId为空。
     *
     * @return 根角色
     */
    private Role getRootRole() {
        Role role = new Role();
        role.setLevel(1);
        role.setParentId(null);
        Example example = new Example(Role.class);
        example.createCriteria().andEqualTo("level",1).andIsNull("parentId");
        return roleMapper.selectOneByExample(example);
    }

}
