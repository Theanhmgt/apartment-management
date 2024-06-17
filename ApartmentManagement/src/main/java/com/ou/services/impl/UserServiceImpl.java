package com.ou.services.impl;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import com.ou.components.FirebaseService;
import com.ou.dto.RoomRegisterDto;
import com.ou.dto.request.ChangePasswordRequest;
import com.ou.dto.request.UserCreationRequest;
import com.ou.dto.response.UserResponse;
import com.ou.exception.AppException;
import com.ou.exception.ErrorCode;
import com.ou.mapper.UserMapper;
import com.ou.pojo.*;
import com.ou.repositories.*;
import com.ou.services.RoomServices;
import com.ou.services.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

@Service("userDetailsService") // chỉ định tên cụ theer
public class UserServiceImpl implements UserService {

    @Autowired
    private MemberInRoomRepository memberInRoomRepository;

    @Autowired
    private CabinetRepository cabinetRepository;

    @Autowired
    private RoomServices roomServices;
    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ContractRepository contractRepository;

    @Autowired
    private ResidentRepository residentRepository;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private BCryptPasswordEncoder passwordEncoder;

    @Autowired
    private FirebaseService firebaseService;

    @Override
    public UserDetails loadUserByUsername(String s) throws UsernameNotFoundException {
        User u = this.userRepository.getUserByUsername(s);
        if (u == null) {
            throw new UsernameNotFoundException("Không tồn tại!");
        }

        Set<GrantedAuthority> authorities = new HashSet<>();
        authorities.add(new SimpleGrantedAuthority(u.getRole()));

        return new org.springframework.security.core.userdetails.User(
                u.getUsername(), u.getPassword(), authorities);
    }


    @Override
    public User getUserByUsername(String username) {
        return userRepository.getUserByUsername(username);
    }

    @Override
    public UserResponse addUser(UserCreationRequest user) {
        if(userRepository.userExistsByUsername(user.getUsername())) {
            throw new AppException(ErrorCode.USER_EXISTED);
        }
        User u = userMapper.toUser(user);
        u.setRole("ROLE_USER");
        u.setPassword(passwordEncoder.encode(user.getPassword()));
        u.setActive(true);
        User newUser =  userRepository.addUser(u);
        return userMapper.toUserResponse(newUser);
    }

    @Override
    public User authUser(String username, String password) {
        return userRepository.authUser(username, password);
    }


    @Override
    public UserResponse getMyInfo() {
        var context = SecurityContextHolder.getContext();
        String username = context.getAuthentication().getName();
        User user = userRepository.getUserByUsername(username);
        return userMapper.toUserResponse(user);
    }

    @Override
    public void createContract(RoomRegisterDto user,Integer roomId) {
        User u = new User();
        if(user.getId() == 0){
            u.setUsername(user.getIdentity());
            u.setPassword(passwordEncoder.encode(user.getIdentity()));
            u.setRole("ROLE_USER");
            userRepository.addUser(u);
        }else {
            u = userRepository.getUserByUsername(user.getIdentity());
        }
        Resident r = userMapper.toResident(user);
        r.setUser(u);
        r.setId(user.getId());
        residentRepository.addResident(r);

        Map<String, Object> rFirebase = new HashMap<>();
        try {
            rFirebase.put("username",r.getIdentity());
            rFirebase.put("name",r.getFullName());
            rFirebase.put("avatar",r.getAvatar());
            this.firebaseService.addUser(rFirebase);
            this.firebaseService.addUserToFirstRoom(r.getIdentity());
        } catch (Exception ex) {
            System.out.println("Something went wrong with firebase");
        }

        Contract c = userMapper.toContract(user);
        if(user.getIdContract() !=0){
            c.setId(user.getIdContract());
        }
        Room room = roomServices.getRoomById(roomId);
        c.setRoom(room);
        c.setResidentUser(r);
        c.setCreatedDate(LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")));
        c.setEndedDate(LocalDate.parse(c.getStartedDate(), DateTimeFormatter.ofPattern("yyyy-MM-dd")).plusMonths(user.getTotalMonth()));
        contractRepository.addContract(c);
        Cabinet cabinet = new Cabinet();
        cabinet.setContract(c);
        cabinet.setCabinetcol("tủ của "+c.getRoom().getNumber());
        cabinetRepository.createCabinet(cabinet);
        roomServices.updateRoom(room);
        if(user.getListMember() != null){
            for (Resident i : user.getListMember()) {
                User mem = new User();
                if(Objects.equals(i.getFullName(), "")){
                    continue;
                }
                if(i.getId() == null){
                    mem.setUsername(i.getIdentity());
                    mem.setPassword(passwordEncoder.encode(i.getIdentity()));
                    mem.setRole("ROLE_USER");
                    userRepository.addUser(mem);
                }else{
                    mem = userRepository.getUserByUsername(i.getIdentity());
                }
                i.setUser(mem);
                i.setId(mem.getId());
                residentRepository.addResident(i);

                try {
                    rFirebase.put("username",i.getIdentity());
                    rFirebase.put("name",i.getFullName());
                    rFirebase.put("avatar",i.getAvatar());
                    this.firebaseService.addUser(rFirebase);
                    this.firebaseService.addUserToFirstRoom(i.getIdentity());
                } catch (Exception ex) {
                    System.out.println("Somwthing went wrong with firebase");
                }

                if(memberInRoomRepository.checkExistence(c,i)){
                    MemberInRoom memberInRoom = new MemberInRoom();
                    memberInRoom.setContract(c);
                    memberInRoom.setResidentUser(i);
                    memberInRoomRepository.addMemberInRoom(memberInRoom);
                }
            }
        }
    }

    @Override
    public void changePassword(ChangePasswordRequest changePasswordRequest) {
        var context = SecurityContextHolder.getContext();
        String username = context.getAuthentication().getName();
        User user = userRepository.getUserByUsername(username);
        if(!this.passwordEncoder.matches(changePasswordRequest.getOldPassword(), user.getPassword())){
            throw new AppException(ErrorCode.INCORRECT_PASSWORD);
        }
        user.setPassword(passwordEncoder.encode(changePasswordRequest.getNewPassword()));
        this.userRepository.changePassword(user);
    }

    @Override
    public User addAdmin(User user) {
        user.setRole("ROLE_ADMIN");
        user.setPassword(passwordEncoder.encode(user.getPassword()));
        user.setActive(true);
        return userRepository.addUser(user);
    }

    @Override
    public boolean userExistsByUsername(String username) {
        return this.userRepository.userExistsByUsername(username);
    }
}
